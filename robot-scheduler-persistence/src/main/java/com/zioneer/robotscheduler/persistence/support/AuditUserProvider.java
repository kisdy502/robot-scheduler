package com.zioneer.robotscheduler.persistence.support;

/**
 * 审计用户提供者：填充 created_by/updated_by。
 *
 * <p>调度服务为机器调用（无用户上下文），默认返回 "system"；
 * 将来接入平台用户体系时提供新实现覆盖。</p>
 */
public interface AuditUserProvider {

    String currentUser();
}
