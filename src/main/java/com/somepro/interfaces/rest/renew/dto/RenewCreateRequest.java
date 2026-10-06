package com.somepro.interfaces.rest.renew.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 办理续当入参（用户接口层）。
 *
 * 用可变 bean + @ModelAttribute：WebFlux 下 application/x-www-form-urlencoded 表单、
 * query string 都能直接绑定。只需指定续的是哪张当票：顺延月数按票上原当期走、
 * 原到期日 / 新到期日由服务端按票算出、续当单号与办理时刻由服务端生成，都不接受外部指定。
 */
@Getter
@Setter
public class RenewCreateRequest {

    /** 续的是哪张当票（t_pawn_ticket.id）。 */
    private Long ticketId;
}
