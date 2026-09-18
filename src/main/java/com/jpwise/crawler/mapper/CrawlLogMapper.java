package com.jpwise.crawler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jpwise.crawler.entity.CrawlLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CrawlLogMapper extends BaseMapper<CrawlLogEntity> {
}
