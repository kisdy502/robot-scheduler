package com.zioneer.robotscheduler.common.core.result;

import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * 通用分页响应（字段 list/total/pageNum/pageSize/pages，JSON 输出 snake_case）。
 *
 * @param <T> 行数据类型
 */
@Getter
@Setter
public class PageResult<T> {

    private List<T> list;
    private Long total;
    private Integer pageNum;
    private Integer pageSize;
    private Integer pages;

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setList(list == null ? Collections.emptyList() : list);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setPages(pageSize <= 0 ? 0 : (int) ((total + pageSize - 1) / pageSize));
        return result;
    }

    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return of(Collections.emptyList(), 0L, pageNum, pageSize);
    }
}
