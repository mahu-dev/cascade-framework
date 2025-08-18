# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Cascade是一个对Redisson进行深度封装的Spring Boot Starter框架，提供统一的分布式能力入口。项目采用模块化设计，包含多级缓存、分布式锁、布隆过滤器、限流器、队列、发布订阅等功能模块。

## 技术栈

- Java 17
- Maven构建工具  
- Spring Boot Starter架构
- Redisson作为底层Redis客户端
- 目标支持多种序列化器(json/Kryo等)
- 集成监控指标(Prometheus/Micrometer)

## 常用命令

```bash
# 编译项目
mvn compile

# 运行测试
mvn test

# 清理并打包
mvn clean package

# 安装到本地仓库
mvn install

# 运行单个测试类
mvn test -Dtest=TestClassName

# 运行单个测试方法
mvn test -Dtest=TestClassName#testMethodName
```

## 项目架构设计

根据设计文档，项目采用以下模块化架构：

```
cascade-spring-boot-starter
├── cascade-core                    # 核心抽象层
├── cascade-cache                   # 多级缓存模块  
├── cascade-lock                    # 分布式锁模块
├── cascade-bloom                   # 布隆过滤器模块
├── cascade-limiter                 # 限流器模块
├── cascade-queue                   # 分布式队列模块
├── cascade-pubsub                  # 发布订阅模块
├── cascade-autoconfigure           # 自动配置模块
└── cascade-spring-boot-starter     # Starter聚合模块
```

## 核心设计理念

1. **统一门面模式**: 通过`Cascade`类提供统一入口，降低学习成本
2. **流式API设计**: 采用Builder模式和链式调用提供优雅的编程体验
3. **深度集成**: 不仅是简单封装，还提供增强功能组合(如缓存+布隆过滤器)
4. **生产就绪**: 内置监控、追踪、降级等企业级特性

## Spring Boot Starter开发约定

- 自动配置类使用`*AutoConfiguration`命名
- 配置属性类使用`*Properties`命名并添加`@ConfigurationProperties`
- 条件注解控制配置生效条件`@ConditionalOn*`
- 在`META-INF/spring.factories`或`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`中声明自动配置

## 主要功能模块

### 缓存模块
- 支持LOCAL_ONLY、REMOTE_ONLY、TIERED等多种模式
- 集成布隆过滤器防穿透
- 提供WRITE_THROUGH、WRITE_BEHIND等写入策略

### 锁模块  
- 支持REENTRANT、FAIR、READ_WRITE、RED_LOCK等多种锁类型
- 提供函数式编程支持
- 自动看门狗续期机制

### 限流模块
- 支持TOKEN_BUCKET、SLIDING_WINDOW等多种算法
- 支持多维度限流(用户、IP等)
- 异步令牌预留机制

### 监控集成
- Prometheus/Micrometer指标导出
- OpenTelemetry链路追踪
- 实时监控事件流