package com.somepro.infrastructure.persistence.renew.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.somepro.infrastructure.persistence.base.BasePO;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * t_pawn_renew 表的持久化对象（PO，基础设施层）。只描述表结构，不放业务规则。
 *
 * 表已由 doc/schema/pawn.sql 建好，列名即契约，本类不做任何建表/改表动作。
 */
@Getter
@Setter
@TableName("t_pawn_renew")
public class PawnRenewPO extends BasePO {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("renew_no")
    private String renewNo;

    @TableField("ticket_id")
    private Long ticketId;

    @TableField("old_due_date")
    private LocalDate oldDueDate;

    @TableField("new_due_date")
    private LocalDate newDueDate;

    @TableField("extend_months")
    private Integer extendMonths;

    @TableField("renewed_at")
    private LocalDateTime renewedAt;
}
