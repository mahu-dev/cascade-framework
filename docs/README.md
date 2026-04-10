# Cascade Framework 文档目录

本目录包含 Cascade Framework 的所有技术文档，按模块分类组织。

## 目录结构

### 📁 framework/
框架整体设计与使用指南
- [快速开始](framework/QUICK_START.md) - 项目快速上手指南
- [框架架构指南](framework/CASCADE_FRAMEWORK_ARCHITECTURE_GUIDE.md) - 整体架构设计
- [使用示例](framework/CASCADE_USAGE_EXAMPLES.md) - 完整使用示例
- [配置优化](framework/CASCADE_CONFIG_OPTIMIZATION.md) - 配置最佳实践

### 📁 cache/
缓存模块相关文档（V2 架构）

#### 核心设计文档
- [架构与功能设计](cache/ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md) - V2 架构详细分析
- [监控与可观测性](cache/README.md) - 指标收集和监控

#### 架构优化文档
- [架构优化分析](cache/ARCHITECTURE_OPTIMIZATION_ANALYSIS.md) - 优化建议与分析
- [注解架构重构](cache/ANNOTATION_ARCHITECTURE_REFACTORING.md) - 注解系统设计

#### 技术分析文档
- [系统架构优化报告](cache/SYSTEM_ARCHITECTURE_OPTIMIZATION_REPORT.md) - 架构演进报告
- [循环依赖修复](cache/CIRCULAR_DEPENDENCY_FIX.md) - 依赖问题解决方案

### 📁 core/
核心模块文档
- [核心模块设计](core/cascade-core-design.md) - 核心抽象层设计

### 📁 autoconfigure/
自动配置模块文档
- [自动配置说明](autoconfigure/README.md) - Spring Boot 自动配置

### 📁 lock/
分布式锁模块文档
- [分布式锁说明](lock/README.md) - 锁模块使用指南

## 文档分类说明

### 设计文档
- 系统架构设计
- 模块功能设计
- 技术方案设计

### 使用指南
- 快速上手教程
- 功能使用示例
- 最佳实践指南

### 技术分析
- 性能优化分析
- 架构演进报告
- 问题解决方案

### 开发文档
- 开发环境配置
- 代码规范说明
- 测试指南

## 快速导航

**新用户推荐阅读顺序：**
1. [快速开始](framework/QUICK_START.md)
2. [框架架构指南](framework/CASCADE_FRAMEWORK_ARCHITECTURE_GUIDE.md)
3. [缓存模块架构设计](cache/ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md)
4. [使用示例](framework/CASCADE_USAGE_EXAMPLES.md)

**深入了解推荐：**
- [V2 架构与功能设计](cache/ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md) - 最新 V2 架构
- [监控与可观测性](cache/README.md) - 指标收集和监控
- [架构优化分析](cache/ARCHITECTURE_OPTIMIZATION_ANALYSIS.md) - 优化建议

## V2 架构说明

Cascade Cache V2 对架构进行了全面重构，主要改进包括：

1. **统一缓存引擎**：注解式与编程式共享同一 EngineBackedCache 内核
2. **职责分离**：按职责拆分为 Core、Read、Write、Eviction、Refresh、Sync、Metrics、Lifecycle
3. **分层存储**：L1（本地）+ L2（分布式）清晰分层
4. **一致性增强**：改进的失效同步机制
5. **性能优化**：优化数据流转和缓存策略

详细内容请参考 [V2 架构与功能设计](cache/ARCHITECTURE_AND_FUNCTIONAL_DESIGN.md)

## 模块说明

### cascade-cache（缓存模块）

**核心特性：**
- 多级缓存：L1（Caffeine）+ L2（Redisson）
- 统一引擎：注解式和编程式完全统一
- 分布式同步：基于 Redis Pub/Sub 的失效同步
- 自动刷新：支持定时刷新和软 TTL 刷新
- 防护机制：SingleFlight 防击穿、分布式锁协调

**技术栈：**
- 本地缓存：Caffeine 3.1.8
- 分布式缓存：Redis（通过 Redisson 3.24.3）
- 框架集成：Spring Boot 3.2、Spring AOP
- 监控指标：Micrometer 1.12.0

### cascade-lock（分布式锁模块）

**核心特性：**
- 可重入锁
- 公平锁
- 读写锁
- 联锁（MultiLock）
- 红锁（RedLock）

### cascade-limiter（限流模块）

**核心特性：**
- 令牌桶算法
- 滑动窗口算法
- 漏桶算法

### cascade-bloom（布隆过滤器模块）

**核心特性：**
- 布隆过滤器
- 布隆过滤器计数器
- 布隆过滤器去重

### cascade-queue（队列模块）

**核心特性：**
- 延迟队列
- 优先级队列
- 有界队列

### cascade-pubsub（发布订阅模块）

**核心特性：**
- 消息发布
- 消息订阅
- 消息监听器

---

*最后更新时间：2025-04-10*
