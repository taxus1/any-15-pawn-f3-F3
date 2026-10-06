package com.somepro.domain.renew.repository;

import com.somepro.domain.renew.model.PawnRenew;
import com.somepro.domain.shared.model.PageResult;
import reactor.core.publisher.Mono;

/**
 * 续当仓储端口（领域层定义，基础设施层实现）。
 *
 * 办理续当（{@link #save}）在写临界区里一并保证四件事：
 * 1. 续当单号全局唯一（XD-年份-序号，一笔一号）：MySQL 命名锁内取当年最大序号 +1，
 *    唯一索引兜底，撞号整段重试，不把底层冲突甩给柜台；
 * 2. 续当规则在锁内按当票【最新】状态复核：不在当、已过到期日都挡回；
 * 3. 同一张票同一业务日只准续出一条：锁内先点这张票今天（办理时刻所在业务日）有没有续过，
 *    有就挡回 —— 柜台手快把同一笔重复递进两回（哪怕两回前后串行、第二回读到了新到期日），
 *    也只成第一笔；真正的下一次续当是改天再来办的另一笔；
 * 4. 到期日期的条件更新 —— 只有库里到期日期仍等于本次记录的「原到期日期」才挪得到期日并落续当账，
 *    作为同日检查之外的并发兜底（两笔在锁外同时读到同一原到期日时，后到者命中 0 行）。
 * 当票到期日期的挪动与续当记录的插入在同一事务里落库，要么一起成、要么一起回滚。
 */
public interface PawnRenewRepository {

    /**
     * 办理：在写锁内复核当票状态与到期日，条件更新当票到期日期（保持在当），
     * 分配雪花 id、生成全局唯一续当单号（XD-年份-序号）并插入续当账，同一事务落库。
     * 当票已不在当 / 已过到期日 / 到期日期已被另一笔续当或改动先挪动时，抛业务异常，整段不落库。
     * 返回回填续当单号与审计字段后的领域对象。
     */
    Mono<PawnRenew> save(PawnRenew renew);

    Mono<PawnRenew> findById(Long id);

    Mono<PawnRenew> findByRenewNo(String renewNo);

    /** 按当票翻续当记录；逻辑删除的不出现，稳定按 id 升序分页，每行带 renewNo。 */
    Mono<PageResult<PawnRenew>> pageByTicket(int pageNum, int pageSize, Long ticketId);
}
