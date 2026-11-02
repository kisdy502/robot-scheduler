package com.zioneer.robotscheduler.common.core.query;

import lombok.Getter;
import lombok.Setter;

/**
 * 通用分页请求（对齐 robot-common-core 的 PageQuery：pageNum=1、pageSize=20、上限 200）。
 */
@Getter
@Setter
public class PageQuery {

    private static final int MAX_PAGE_SIZE = 200;

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public int safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
