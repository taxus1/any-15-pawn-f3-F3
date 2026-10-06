package com.somepro.infrastructure.persistence.renew.converter;

import com.somepro.domain.renew.model.PawnRenew;
import com.somepro.infrastructure.persistence.renew.po.PawnRenewPO;

/**
 * PawnRenewPO（表）↔ PawnRenew（领域）转换器（基础设施层），PO 不外泄。
 * 本表没有枚举列，都是普通字段直拷。
 */
public final class PawnRenewPoConverter {

    private PawnRenewPoConverter() {
    }

    public static PawnRenewPO toPo(PawnRenew domain) {
        PawnRenewPO po = new PawnRenewPO();
        po.setId(domain.getId());
        po.setRenewNo(domain.getRenewNo());
        po.setTicketId(domain.getTicketId());
        po.setOldDueDate(domain.getOldDueDate());
        po.setNewDueDate(domain.getNewDueDate());
        po.setExtendMonths(domain.getExtendMonths());
        po.setRenewedAt(domain.getRenewedAt());
        po.setDelFlag(domain.getDelFlag());
        po.setCreateBy(domain.getCreateBy());
        po.setCreateTime(domain.getCreateTime());
        po.setUpdateBy(domain.getUpdateBy());
        po.setUpdateTime(domain.getUpdateTime());
        return po;
    }

    public static PawnRenew toDomain(PawnRenewPO po) {
        PawnRenew domain = new PawnRenew();
        domain.setId(po.getId());
        domain.setRenewNo(po.getRenewNo());
        domain.setTicketId(po.getTicketId());
        domain.setOldDueDate(po.getOldDueDate());
        domain.setNewDueDate(po.getNewDueDate());
        domain.setExtendMonths(po.getExtendMonths());
        domain.setRenewedAt(po.getRenewedAt());
        domain.setDelFlag(po.getDelFlag());
        domain.setCreateBy(po.getCreateBy());
        domain.setCreateTime(po.getCreateTime());
        domain.setUpdateBy(po.getUpdateBy());
        domain.setUpdateTime(po.getUpdateTime());
        return domain;
    }
}
