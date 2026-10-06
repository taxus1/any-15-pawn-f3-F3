package com.somepro.infrastructure.persistence.renew.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 续当模块眼里的 t_pawn_ticket（PO，基础设施层）：只映射续当要读、要挪的几列。
 *
 * 与当票模块自己的 PawnTicketPO 各管各的视角（与 TicketCollateralPO 的取舍一致）：
 * 这里只取 id / due_date / term_months / status，用于锁内复核续当条件
 * （在当、未过到期日、当期月数）以及把到期日期往后挪的条件更新。
 * 条件更新走 BaseMapper（entity + wrapper），update_time 由 MetaObjectHandler 自动填充，
 * 与续当账插入在同一事务里执行。本类不做任何建表/改表动作。
 */
@Getter
@Setter
@TableName("t_pawn_ticket")
public class RenewTicketPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("due_date")
    private LocalDate dueDate;

    @TableField("term_months")
    private Integer termMonths;

    @TableField("status")
    private String status;
}
