package com.somepro.infrastructure.persistence.renew;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.renew.po.PawnRenewPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 续当 Mapper（基础设施层）。
 *
 * BaseMapper 覆盖常规 CRUD；续当单号生成需要自定义语义，用注解 SQL 写死，不建 XML。
 * 写临界区的命名锁不走 MyBatis（要用独立于事务的连接持锁），见 PawnRenewRepositoryImpl#inWriteLock。
 *
 * 阻塞 JDBC API，只能在仓储适配器的 blocking(...) 桥接里调用。
 */
@Mapper
public interface PawnRenewMapper extends BaseMapper<PawnRenewPO> {

    /**
     * 取某年全部续当单号（序号在 Java 侧取最大，只选 renew_no 一列，数据量小）。
     *
     * 刻意不带 del_flag = 0：续当单号一经分配永久占用 —— 哪怕那笔账后来被删除，
     * 它的号也不能再发给新办理的续当（否则同一 XD 号在账上先后指向两笔办理）。
     * 不能直接 ORDER BY 字符串 DESC LIMIT 1：字符串排序下 XD-2026-9999 会排在 XD-2026-10000 前面。
     */
    @Select("SELECT renew_no FROM t_pawn_renew WHERE renew_no LIKE #{prefix}")
    List<String> findRenewNosByPrefix(@Param("prefix") String prefix);

    /**
     * 点这张当票在给定时间区间内（一个业务日的 [00:00, 次日00:00)）已经续过几笔。
     * 只在写锁内调用：> 0 就挡回，同一张票同一业务日只准续出一条
     * （柜台手快把同一笔重复递进两回，也只成第一笔）。
     * 区间边界在 Java 侧按行里时区算好再传，避免数据库会话时区影响「算哪一天」。
     */
    @Select("SELECT COUNT(*) FROM t_pawn_renew WHERE ticket_id = #{ticketId} "
            + "AND renewed_at >= #{dayStart} AND renewed_at < #{nextDayStart} AND del_flag = 0")
    long countByTicketOnDay(@Param("ticketId") Long ticketId,
                            @Param("dayStart") java.time.LocalDateTime dayStart,
                            @Param("nextDayStart") java.time.LocalDateTime nextDayStart);
}
