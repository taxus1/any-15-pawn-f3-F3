package com.somepro.interfaces.rest.renew;

import com.somepro.application.renew.PawnRenewAppService;
import com.somepro.common.Result;
import com.somepro.interfaces.rest.common.vo.PageVO;
import com.somepro.interfaces.rest.renew.converter.PawnRenewVoConverter;
import com.somepro.interfaces.rest.renew.dto.RenewCreateRequest;
import com.somepro.interfaces.rest.renew.vo.PawnRenewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 续当模块用户接口层：办理续当、查看单笔、按当票翻续当记录。
 *
 * 只做协议适配（参数解析、VO 转换、Result 包装），业务编排在 {@link PawnRenewAppService}。
 * 入参统一走 @ModelAttribute / @RequestParam：表单 / query string / x-www-form-urlencoded 都能接，
 * 便于柜台端直接调用。
 */
@RestController
@RequestMapping("/api/renew")
public class RenewController {

    private final PawnRenewAppService pawnRenewAppService;

    public RenewController(PawnRenewAppService pawnRenewAppService) {
        this.pawnRenewAppService = pawnRenewAppService;
    }

    /**
     * 办理续当：只认在当、且未过到期日的票；顺延月数按票上原当期走，
     * 新到期日从原到期日往后推，票续完仍留在当。续当单号 XD-年份-序号 由服务端生成，
     * 同一张票同一时点重复递交只成一笔。
     */
    @PostMapping("/create")
    public Mono<Result<PawnRenewVO>> create(@ModelAttribute RenewCreateRequest request) {
        return pawnRenewAppService.renew(request.getTicketId())
                .map(PawnRenewVoConverter::toVo)
                .map(Result::ok);
    }

    /** 查看单笔：id 或 renewNo 任一指定；带出原/新到期日、顺延月数、办理时刻与对应当票票号。 */
    @GetMapping("/detail")
    public Mono<Result<PawnRenewVO>> detail(@RequestParam(required = false) Long id,
                                            @RequestParam(required = false) String renewNo) {
        return pawnRenewAppService.detail(id, renewNo)
                .map(PawnRenewVoConverter::toVo)
                .map(Result::ok);
    }

    /**
     * 按当票翻续当记录：ticketId 或 ticketNo 任一指定，分页一页页走；
     * 每行带 renewNo 便于与纸质续当凭证对号。
     */
    @GetMapping("/list")
    public Mono<Result<PageVO<PawnRenewVO>>> list(@RequestParam(required = false) Long ticketId,
                                                  @RequestParam(required = false) String ticketNo,
                                                  @RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        return pawnRenewAppService.page(ticketId, ticketNo, pageNum, pageSize)
                .map(PawnRenewVoConverter::toPageVo)
                .map(Result::ok);
    }
}
