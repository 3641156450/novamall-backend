package com.novamall.common.result;

import lombok.Data;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果封装。
 *
 * <p>不用 MyBatis-Plus 的 IPage 直接返回给前端：IPage 里带了大量 SQL 层信息
 * （如 total、optimizeCountSql、orders），暴露出去既不安全也不稳定。
 * 这里做一次领域对象转换。</p>
 *
 * @author NovaMall
 */
@Data
public class PageResult<T> {

    private long total;
    private long pageNo;
    private long pageSize;
    private List<T> records = Collections.emptyList();

    public static <T> PageResult<T> of(long total, long pageNo, long pageSize, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPageNo(pageNo);
        result.setPageSize(pageSize);
        result.setRecords(records == null ? Collections.emptyList() : records);
        return result;
    }
}
