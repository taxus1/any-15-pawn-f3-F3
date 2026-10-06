package com.somepro.interfaces.rest.renew.vo;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 续当对外对象（不可变 record）：办理 / 查看单笔 / 翻记录共用。
 *
 * 每行都带 renewNo（XD-年份-序号），方便柜台跟纸质续当凭证对号；
 * ticketNo 带出续的是哪张当票，oldDueDate / newDueDate / extendMonths 说明这次往后挪了多久。
 * 刻意不暴露 delFlag / createBy / updateBy 等内部字段。
 */
public record PawnRenewVO(Long id,
                          String renewNo,
                          Long ticketId,
                          String ticketNo,
                          LocalDate oldDueDate,
                          LocalDate newDueDate,
                          Integer extendMonths,
                          LocalDateTime renewedAt) implements Serializable {
}
