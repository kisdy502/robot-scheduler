package com.zioneer.robotscheduler.persistence.support;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

/**
 * 审计字段自动填充：created_at/updated_at/created_by/updated_by/is_deleted。
 */
public class CommonFieldMetaObjectHandler implements MetaObjectHandler {

    static final String DEFAULT_USER = "system";

    private final AuditUserProvider auditUserProvider;

    public CommonFieldMetaObjectHandler(AuditUserProvider auditUserProvider) {
        this.auditUserProvider = auditUserProvider;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        this.strictInsertFill(metaObject, "createdAt", Instant.class, now);
        this.strictInsertFill(metaObject, "updatedAt", Instant.class, now);
        this.strictInsertFill(metaObject, "createdBy", String.class, currentUser());
        this.strictInsertFill(metaObject, "updatedBy", String.class, currentUser());
        this.strictInsertFill(metaObject, "deleted", Boolean.class, Boolean.FALSE);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", Instant.class, Instant.now());
        this.strictUpdateFill(metaObject, "updatedBy", String.class, currentUser());
    }

    private String currentUser() {
        String user = auditUserProvider == null ? null : auditUserProvider.currentUser();
        return user == null || user.isBlank() ? DEFAULT_USER : user;
    }
}
