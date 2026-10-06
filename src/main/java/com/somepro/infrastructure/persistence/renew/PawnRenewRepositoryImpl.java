package com.somepro.infrastructure.persistence.renew;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.somepro.common.exception.BizException;
import com.somepro.domain.collateral.model.Category;
import com.somepro.domain.renew.model.PawnRenew;
import com.somepro.domain.renew.repository.PawnRenewRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.model.TicketStatus;
import com.somepro.infrastructure.config.ReactiveOperatorContext;
import com.somepro.infrastructure.persistence.audit.AuditContextHolder;
import com.somepro.infrastructure.persistence.renew.converter.PawnRenewPoConverter;
import com.somepro.infrastructure.persistence.renew.po.PawnRenewPO;
import com.somepro.infrastructure.persistence.renew.po.RenewTicketPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 续当仓储适配器（基础设施层）：MyBatis-Plus 阻塞 JDBC 经 blocking(...) 桥接进响应式链路。
 *
 * 本类三处关键业务语义：
 *
 * 1. 续当办理与当票写临界区共用同一把锁
 *    续当要挪动 t_pawn_ticket 的到期日期，和开票 / 撤销改的是同一张表的同一批票，
 *    所以直接复用当票开立的命名锁 GET_LOCK('pawn_ticket:write')（全实例同名互斥）。
 *    两笔续当、以及续当与撤销 / 开票之间都不会在临界区里打架。
 *
 * 2. 续当单号 XD-yyyy-NNNN
 *    同一把写锁内：取当年续当单号的最大整数序号 +1（序号在 Java 侧解析，
 *    避免字符串排序把 9999 排在 10000 前），锁内算号天然不撞；
 *    取号刻意包含已删除的续当账：单号一经分配永久占用。
 *    uk_renew_no 唯一索引是最后防线，极端瞬态冲突整段重试，不甩底层错给柜台。
 *
 * 3. 同票同业务日只准一条 + 到期日期条件更新 = 重复递交只成一笔
 *    锁内先点这张票办理时刻所在业务日有没有续过账：有就挡回 —— 柜台手快把同一笔重复递进
 *    两回，哪怕两回前后串行（第二回已读到第一笔挪出的新到期日），也只成第一笔；
 *    真正的下一次续当是改天再来办的另一笔。
 *    随后挪到期日仍带条件：UPDATE t_pawn_ticket SET due_date=新值
 *    WHERE id=? AND status='ACTIVE' AND due_date=原到期日，作为同日检查之外的并发兜底。
 *    更新行与续当账插入同一事务，票的到期日与续当账要么一起成、要么一起回滚，票的状态始终留在当。
 *
 * 锁的连接与时序同当票模块：用一条【独立于事务的原始连接】在事务开启前 GET_LOCK、
 * 在事务【提交之后】才 RELEASE_LOCK，避免「锁已放、事务未提交」导致后到者读到旧到期日。
 */
@Repository
public class PawnRenewRepositoryImpl implements PawnRenewRepository {

    /** 业务日期统一按行里所在时区算，避免容器 UTC 下单号跨年、到期判断偏一天。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    /** 与当票开立 / 撤销共用的写临界区命名锁（MySQL 全实例同名互斥）。 */
    private static final String WRITE_LOCK = "pawn_ticket:write";
    private static final int LOCK_WAIT_SECONDS = 10;
    private static final int MAX_RETRY = 5;

    private final PawnRenewMapper pawnRenewMapper;
    private final RenewTicketMapper renewTicketMapper;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;

    public PawnRenewRepositoryImpl(PawnRenewMapper pawnRenewMapper,
                                   RenewTicketMapper renewTicketMapper,
                                   PlatformTransactionManager transactionManager,
                                   DataSource dataSource) {
        this.pawnRenewMapper = pawnRenewMapper;
        this.renewTicketMapper = renewTicketMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
    }

    @Override
    public Mono<PawnRenew> save(PawnRenew renew) {
        return blocking(() -> {
            // 每轮重试用独立连接重新抢锁；兜住单号撞号 / 锁等待超时等瞬态冲突
            for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
                try {
                    return inWriteLock(() -> transactionTemplate.execute(status -> {
                        // 锁内重读当票最新状态：应用层预读之后这张票可能已被续、被赎、被绝当、被撤销
                        RenewTicketPO ticket = renewTicketMapper.selectById(renew.getTicketId());
                        if (ticket == null) {
                            throw new BizException("当票不存在，不能续当");
                        }
                        PawnTicket latest = toTicketDomain(ticket);
                        // 用最新到期日重新走一遍聚合规则：不在当 / 已过到期日都在这里挡回
                        PawnRenew register = PawnRenew.register(latest, renew.getRenewedAt());

                        // 同票同业务日只准一条：柜台手快重复递进（即便前后串行、第二笔读到了新到期日）
                        // 也只成第一笔。区间按行里时区在 Java 侧算，临界区在锁内串行，查得到就是刚续的
                        LocalDate bizDate = renew.getRenewedAt().toLocalDate();
                        long already = pawnRenewMapper.countByTicketOnDay(
                                renew.getTicketId(),
                                bizDate.atStartOfDay(),
                                bizDate.plusDays(1).atStartOfDay());
                        if (already > 0) {
                            throw new BizException("该当票今天已经办过续当，同一时点重复递交不会重复记账；"
                                    + "如需再次续当请改天办理");
                        }

                        // 条件更新：库里到期日必须仍是本次的「原到期日」，且票仍在当。
                        // 同日检查之外的并发兜底：极端情况下两笔带着同一原到期日走到这里，
                        // 第一笔把到期日挪走，后到者命中 0 行，整段回滚，续当账不多落
                        RenewTicketPO dueUpdate = new RenewTicketPO();
                        dueUpdate.setId(ticket.getId());
                        dueUpdate.setDueDate(register.getNewDueDate());
                        int rows = renewTicketMapper.update(dueUpdate,
                                Wrappers.<RenewTicketPO>lambdaUpdate()
                                        .eq(RenewTicketPO::getId, ticket.getId())
                                        .eq(RenewTicketPO::getStatus, TicketStatus.ACTIVE.code())
                                        .eq(RenewTicketPO::getDueDate, register.getOldDueDate()));
                        if (rows == 0) {
                            throw new BizException("该当票刚刚已被续当或状态已变化，请刷新后按最新到期日办理；"
                                    + "同一时点重复递交不会重复记账");
                        }

                        PawnRenewPO po = PawnRenewPoConverter.toPo(register);
                        po.setId(IdUtil.getSnowflakeNextId());
                        po.setRenewNo(nextRenewNo());
                        pawnRenewMapper.insert(po);
                        return PawnRenewPoConverter.toDomain(po);
                    }));
                } catch (DuplicateKeyException | TransientDataAccessException e) {
                    // uk_renew_no 是最后防线，锁内正常不会撞；撞了整段重新取号重试
                    if (attempt == MAX_RETRY - 1) {
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                    try {
                        Thread.sleep(10L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BizException("系统繁忙，请稍后重试");
                    }
                }
            }
            throw new BizException("系统繁忙，请稍后重试");
        });
    }

    @Override
    public Mono<PawnRenew> findById(Long id) {
        return blocking(() -> {
            PawnRenewPO po = pawnRenewMapper.selectById(id);
            return po == null ? null : PawnRenewPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PawnRenew> findByRenewNo(String renewNo) {
        return blocking(() -> {
            PawnRenewPO po = pawnRenewMapper.selectOne(
                    Wrappers.<PawnRenewPO>lambdaQuery().eq(PawnRenewPO::getRenewNo, renewNo));
            return po == null ? null : PawnRenewPoConverter.toDomain(po);
        });
    }

    @Override
    public Mono<PageResult<PawnRenew>> pageByTicket(int pageNum, int pageSize, Long ticketId) {
        return this.<PageResult<PawnRenew>>blocking(() -> {
            try {
                PageHelper.startPage(pageNum, pageSize);
                LambdaQueryWrapper<PawnRenewPO> wrapper = Wrappers.<PawnRenewPO>lambdaQuery()
                        .eq(PawnRenewPO::getTicketId, ticketId)
                        // 稳定排序：按办理先后翻页，两页之间不会出现同一笔
                        .orderByAsc(PawnRenewPO::getId);
                List<PawnRenewPO> rows = pawnRenewMapper.selectList(wrapper);
                long total = rows instanceof com.github.pagehelper.Page
                        ? ((com.github.pagehelper.Page<?>) rows).getTotal()
                        : rows.size();
                List<PawnRenew> content = rows.stream()
                        .map(PawnRenewPoConverter::toDomain)
                        .collect(Collectors.toList());
                return new PageResult<>(content, total, pageNum, pageSize);
            } finally {
                // PageHelper 靠 ThreadLocal 传分页参数，必须清，避免污染线程池下一次调用
                PageHelper.clearPage();
            }
        });
    }

    /** 锁内读到的当票瘦视角 → 领域聚合，只填续当规则用得到的字段。 */
    private PawnTicket toTicketDomain(RenewTicketPO po) {
        PawnTicket ticket = new PawnTicket();
        ticket.setId(po.getId());
        ticket.setDueDate(po.getDueDate());
        ticket.setTermMonths(po.getTermMonths());
        ticket.setStatus(po.getStatus() == null ? null : TicketStatus.valueOf(po.getStatus()));
        // 下列字段续当规则不读；category 是枚举，给个占位避免 null 语义含糊（不会参与续当判断）
        ticket.setCategory(Category.OTHER);
        return ticket;
    }

    /**
     * 生成 XD-年份-序号：序号是当年已有续当单号（含已删除）最大整数 +1，至少 4 位、超出自然进位。
     * 只在写锁（{@link #inWriteLock}）内调用，锁内串行所以不会撞号；
     * renew_no 唯一索引是最后防线，极端瞬态冲突由外层整段重试兜底。
     */
    private String nextRenewNo() {
        int year = LocalDate.now(BIZ_ZONE).getYear();
        String prefix = "XD-" + year + "-";
        long maxSeq = 0L;
        for (String no : pawnRenewMapper.findRenewNosByPrefix(prefix + "%")) {
            if (no == null || !no.startsWith(prefix)) {
                continue;
            }
            String tail = no.substring(prefix.length());
            if (tail.chars().allMatch(Character::isDigit)) {
                maxSeq = Math.max(maxSeq, Long.parseLong(tail));
            }
        }
        return prefix + String.format("%04d", maxSeq + 1);
    }

    /**
     * 在全局命名锁保护下执行一段【含事务】的写入：锁由一条独立原始连接持有，
     * 在事务开始前 GET_LOCK、在事务提交/回滚之后才 RELEASE_LOCK（顺序不能颠倒）。
     *
     * 为什么锁要走独立连接而不是 MyBatis 连接：GET_LOCK 绑定连接；
     * 若用事务所在连接，Spring 提交时归还连接会立刻放锁，存在「锁已放、事务未提交」的窗口，
     * 后到的事务读到的还是旧到期日，条件更新就可能漏放重复账。独立连接持锁可把锁保到提交之后。
     */
    private <T> T inWriteLock(Supplier<T> action) {
        Connection lockConn;
        try {
            lockConn = dataSource.getConnection();
        } catch (SQLException e) {
            throw new BizException("系统繁忙，请稍后重试");
        }
        try {
            if (!namedLock(lockConn, true)) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            try {
                return action.get();
            } finally {
                // 此时 action 内的事务已提交（或回滚），放锁后后到者必能看到本次到期日挪动
                namedLock(lockConn, false);
            }
        } finally {
            try {
                lockConn.close();
            } catch (SQLException ignored) {
                // 连接关闭会自动释放其上的命名锁，不影响主流程
            }
        }
    }

    /** GET_LOCK / RELEASE_LOCK；返回 MySQL 结果（1 成功）。 */
    private boolean namedLock(Connection conn, boolean get) {
        String sql = get ? "SELECT GET_LOCK(?, ?)" : "SELECT RELEASE_LOCK(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, WRITE_LOCK);
            if (get) {
                ps.setInt(2, LOCK_WAIT_SECONDS);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int r = rs.getInt(1);
                    return !rs.wasNull() && r == 1;
                }
                return false;
            }
        } catch (SQLException e) {
            if (get) {
                throw new BizException("系统繁忙，请稍后重试");
            }
            return false;
        }
    }

    /**
     * 阻塞 DB 调用 → 响应式链路桥接器：先从 Reactor Context 取操作人，再切到 boundedElastic，
     * 操作人放进 AuditContextHolder 供审计填充（与当票模块同一套约定，顺序不能颠倒）。
     */
    private <T> Mono<T> blocking(Supplier<T> supplier) {
        return Mono.deferContextual(ctx -> {
            String operator = ReactiveOperatorContext.getOperator(ctx);
            return Mono.fromCallable(() -> {
                AuditContextHolder.setOperator(operator);
                try {
                    return supplier.get();
                } finally {
                    AuditContextHolder.clear();
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }
}
