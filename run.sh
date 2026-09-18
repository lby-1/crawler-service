#!/bin/bash
echo "=========================================="
echo "  JPwise Crawler Service - 启动脚本"
echo "=========================================="

# 设置环境变量（生产环境通过环境变量注入）
# export MYSQL_DB_PASSWORD=your_password
# export CRAWLER_AUTH_TOKEN=your_token

echo ""
echo "1. 编译项目..."
mvn clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "编译失败！"
    exit 1
fi

echo "2. 启动服务 (端口 8090)..."
echo ""
echo "测试接口:"
echo "  查看可用分类: http://localhost:8090/api/crawl/categories"
echo "  快速测试爬取: http://localhost:8090/api/crawl/test?category=zbgg&pages=1"
echo "  含详情爬取:   http://localhost:8090/api/crawl/test/detail?category=zbgg&pages=1"
echo "  健康检查:     http://localhost:8090/api/health"
echo "  查看结果:     http://localhost:8090/api/results/all"
echo ""

java -jar target/crawler-service-1.0.0.jar
