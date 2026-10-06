package com.somepro.interfaces.rest.renew.converter;

import com.somepro.application.renew.PawnRenewAppService.RenewPageView;
import com.somepro.application.renew.PawnRenewAppService.RenewView;
import com.somepro.domain.renew.model.PawnRenew;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.renew.vo.PawnRenewVO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 续当应用组合值 → VO 转换器（用户接口层）。Controller 不直接把领域对象塞进 Result。
 */
public final class PawnRenewVoConverter {

    private PawnRenewVoConverter() {
    }

    public static PawnRenewVO toVo(RenewView view) {
        return toVo(view.renew(), view.ticketNo());
    }

    public static PawnRenewVO toVo(PawnRenew domain, String ticketNo) {
        return new PawnRenewVO(
                domain.getId(),
                domain.getRenewNo(),
                domain.getTicketId(),
                ticketNo,
                domain.getOldDueDate(),
                domain.getNewDueDate(),
                domain.getExtendMonths(),
                domain.getRenewedAt());
    }

    /** 翻记录分页：一页记录同属一张票，票号带在每行里，外层仍是统一 PageVO。 */
    public static PageVO<PawnRenewVO> toPageVo(RenewPageView view) {
        List<PawnRenewVO> content = view.page().content().stream()
                .map(renew -> toVo(renew, view.ticketNo()))
                .collect(Collectors.toList());
        return new PageVO<>(content, view.page().total(),
                view.page().pageNum(), view.page().pageSize(), view.page().totalPages());
    }
}
