package com.somepro.application.renew;

import com.somepro.common.exception.BizException;
import com.somepro.domain.renew.model.PawnRenew;
import com.somepro.domain.renew.repository.PawnRenewRepository;
import com.somepro.domain.shared.model.PageResult;
import com.somepro.domain.ticket.model.PawnTicket;
import com.somepro.domain.ticket.repository.PawnTicketRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 续当应用服务：编排办理续当、查看单笔、按当票翻续当记录三个用例，不写表映射。
 *
 * 出入参用领域对象/基础类型，不认识 PO 与 VO。
 *
 * 办理这条链在这里收口：先认票（读当票）→ 聚合卡办理条件（在当、未过到期日、算新到期日）
 * → 仓储在写锁内按当票最新状态复核、条件挪动到期日、生成续当单号落账。
 * 单号唯一与「同票同时点只成一笔」的并发约束在仓储里；单次办理自身规则在 PawnRenew 聚合里。
 */
@Service
public class PawnRenewAppService {

    /** 业务时间统一按行里所在时区算，避免容器 UTC 下把到期判断偏到后一天。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final PawnRenewRepository pawnRenewRepository;
    private final PawnTicketRepository pawnTicketRepository;

    public PawnRenewAppService(PawnRenewRepository pawnRenewRepository,
                               PawnTicketRepository pawnTicketRepository) {
        this.pawnRenewRepository = pawnRenewRepository;
        this.pawnTicketRepository = pawnTicketRepository;
    }

    /**
     * 办理续当：只有在当、且办理当日不晚于到期日的票才办得了；顺延月数按票上原当期走，
     * 新到期日从原到期日往后推，票续完仍留在当。办理时刻由服务端按行里时区落账，不接受外部指定。
     * 同一张票同一时点重复递进只成一笔（仓储条件更新保证）。
     */
    public Mono<RenewView> renew(Long ticketId) {
        return requireTicket(ticketId).flatMap(ticket -> {
            // 先按预读的票走一遍聚合规则，给柜台干净的业务提示；锁内仓储还会按最新状态复核
            PawnRenew domain = PawnRenew.register(ticket, LocalDateTime.now(BIZ_ZONE));
            return pawnRenewRepository.save(domain).map(saved -> new RenewView(saved, ticket.getTicketNo()));
        });
    }

    /** 查看单笔：id 或 renewNo（XD-编号）任一指定；带上对应当票的票号便于对号。 */
    public Mono<RenewView> detail(Long id, String renewNo) {
        Mono<PawnRenew> found;
        if (id != null) {
            found = pawnRenewRepository.findById(id)
                    .switchIfEmpty(Mono.error(new BizException("续当记录不存在")));
        } else if (renewNo != null && !renewNo.isBlank()) {
            found = pawnRenewRepository.findByRenewNo(renewNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("续当记录不存在")));
        } else {
            return Mono.error(new BizException("请指定要查看的续当记录（id 或 renewNo）"));
        }
        return found.flatMap(renew -> requireTicket(renew.getTicketId())
                .map(ticket -> new RenewView(renew, ticket.getTicketNo())));
    }

    /**
     * 按当票翻续当记录：ticketId 或 ticketNo（DP-编号）任一指定，分页一页页走，
     * 每行带续当单号；同一页上的记录都属于同一张票，票号带一次即可。
     */
    public Mono<RenewPageView> page(Long ticketId, String ticketNo, int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1) {
            return Mono.error(new BizException("页码与每页条数必须为正整数"));
        }
        return resolveTicket(ticketId, ticketNo).flatMap(ticket -> pawnRenewRepository
                .pageByTicket(pageNum, pageSize, ticket.getId())
                .map(page -> new RenewPageView(page, ticket.getTicketNo())));
    }

    private Mono<PawnTicket> requireTicket(Long id) {
        if (id == null) {
            return Mono.error(new BizException("必须指定当票 id"));
        }
        return pawnTicketRepository.findById(id)
                .switchIfEmpty(Mono.error(new BizException("当票不存在")));
    }

    /** list 入口：id 或票号任一指定，都不给空查询（续当记录总是挂在一张票上翻）。 */
    private Mono<PawnTicket> resolveTicket(Long ticketId, String ticketNo) {
        if (ticketId != null) {
            return requireTicket(ticketId);
        }
        if (ticketNo != null && !ticketNo.isBlank()) {
            return pawnTicketRepository.findByTicketNo(ticketNo.trim())
                    .switchIfEmpty(Mono.error(new BizException("当票不存在")));
        }
        return Mono.error(new BizException("请指定要翻哪张当票的续当记录（ticketId 或 ticketNo）"));
    }

    /** 应用层内部组合值：一笔续当账 + 对应当票票号，接口层据此转 VO。 */
    public record RenewView(PawnRenew renew, String ticketNo) {
    }

    /** 应用层内部组合值：一页续当账 + 这页记录所属当票的票号。 */
    public record RenewPageView(PageResult<PawnRenew> page, String ticketNo) {
    }
}
