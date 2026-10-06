package com.somepro.infrastructure.persistence.renew;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.renew.po.RenewTicketPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 续当模块读写当票表的 Mapper（基础设施层）：锁内读当票最新状态、把到期日期往后挪。
 *
 * 到期日期挪动走 BaseMapper 的条件更新（entity + wrapper：id + status=ACTIVE + due_date=原到期日），
 * 条件不满足更新行数为 0，整段事务回滚、续当账也不落 —— 同一张票同一时点重复递进只成一笔。
 * update_time 由 MetaObjectHandler 自动填充；不动 status，续完票仍是在当。
 *
 * 阻塞 JDBC API，只能在仓储适配器的 blocking(...) 桥接里调用。
 */
@Mapper
public interface RenewTicketMapper extends BaseMapper<RenewTicketPO> {
}
