package com.somepro.domain.renew.model;

import com.somepro.common.exception.BizException;
import com.somepro.domain.shared.model.BaseEntity;
import com.somepro.domain.ticket.model.PawnTicket;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 续当记录（纯领域对象，不带任何持久化注解）。
 *
 * 一条记录 = 一次续当办理。核心规则：
 * 1. 只有在当（ACTIVE）的票才续得了 —— 已赎 / 已绝当 / 已撤销都是定了案的历史票，不办续当；
 * 2. 得赶在票到期的那一天或之前来办：办理当日（业务日期）晚于票上到期日期就该走别的路子，不能再续；
 * 3. 顺延月数不另报，按当票原本的当期月数（termMonths）走；
 *    新到期日期 = 原到期日期 + 这么多个月（plusMonths），不是从办理日重起当期；
 * 4. 续完票的状态仍是在当，只是到期日期往后挪 —— 同一张票可以这样续很多次，
 *    每次都以上一次续完的到期日期为「原本到期日期」再往后推。
 *
 * 续当不产生新票，只把对应当票的到期日期往后挪，并在本表留一笔账。
 * 续当单号 renewNo（XD-2026-0001 样式）由仓储按当年序号生成，全局唯一、一笔一号；
 * 「同一张票同一时点重复递交只成一笔」是跨请求的并发约束，由仓储在写锁内
 * （同业务日检查 + 到期日期条件更新）保证，本聚合只管单次办理自身的规则。
 */
@Getter
@Setter
public class PawnRenew extends BaseEntity {

    private Long id;

    /** 续当单号，如 XD-2026-0001；办理时由仓储生成，业务上不可改。 */
    private String renewNo;

    /** 续的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;

    /** 续当前到期日期，从当票当时的到期日期抄录。 */
    private LocalDate oldDueDate;

    /** 续当后到期日期 = 原到期日期 + 当期月数。 */
    private LocalDate newDueDate;

    /** 本次顺延月数，取自当票原本的当期月数。 */
    private Integer extendMonths;

    /** 续当办理时刻，由应用层按行里时区取当下时刻传入。 */
    private LocalDateTime renewedAt;

    /**
     * 工厂方法：办理一次续当。
     *
     * @param ticket     要续的当票（按最新库内状态读出）
     * @param renewedAt  办理时刻；其日期部分用来判断「是否已过到期日」，时刻部分原样落账
     */
    public static PawnRenew register(PawnTicket ticket, LocalDateTime renewedAt) {
        if (ticket == null || ticket.getId() == null) {
            throw new BizException("必须指定要续当的当票");
        }
        if (renewedAt == null) {
            throw new BizException("续当办理时刻不能为空");
        }
        // 只有在当的票才轮得到续；已赎 / 已绝当 / 已撤销都不给续
        if (!ticket.isActive()) {
            throw new BizException("只有在当的当票才能续当，当前状态："
                    + (ticket.getStatus() == null ? "-" : ticket.getStatus().label()));
        }
        LocalDate oldDueDate = ticket.getDueDate();
        if (oldDueDate == null) {
            throw new BizException("当票缺少到期日期，不能续当");
        }
        // 得赶在到期那一天或之前来办；到期日当天仍可续，过了这一天就该走别的路子
        LocalDate bizDate = renewedAt.toLocalDate();
        if (bizDate.isAfter(oldDueDate)) {
            throw new BizException("已过到期日期（" + oldDueDate + "），不能再续当，请走绝当等其它路子");
        }
        Integer termMonths = ticket.getTermMonths();
        if (termMonths == null || termMonths < 1) {
            throw new BizException("当票当期月数非法，不能续当");
        }

        PawnRenew renew = new PawnRenew();
        renew.ticketId = ticket.getId();
        renew.oldDueDate = oldDueDate;
        // 顺延月数按当票原当期走，新到期日从【原到期日】往后推（不是从办理日重起）
        renew.extendMonths = termMonths;
        renew.newDueDate = oldDueDate.plusMonths(termMonths);
        renew.renewedAt = renewedAt;
        return renew;
    }
}
