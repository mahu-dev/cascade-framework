# Cascade Framework - Gemini Context

## Project Overview

This repository contains the **Cascade Framework**, a multi-module Java project built with Maven. The framework provides a deep encapsulation of the [Redisson](https://redisson.org/) library to offer robust, enterprise-grade distributed computing components.

The core purpose of Cascade is to simplify the implementation of common distributed patterns by providing a unified, fluent API. It leverages a multi-level architecture, primarily for its caching solution, which combines in-memory (local) caching with a distributed Redis-based cache.

**Key Technologies:**
*   **Language:** Java 17
*   **Core Framework:** Spring Boot 3.2
*   **Distributed Computing:** Redisson 3.24
*   **Local Caching:** Caffeine 3.1
*   **Metrics:** Micrometer
*   **Build:** Maven

**Architecture:**
The framework is designed in a modular fashion, with each module encapsulating a specific distributed feature:
*   `cascade-core`: Provides the central `Cascade` facade and shared utilities.
*   `cascade-cache`: A sophisticated multi-level cache (Caffeine + Redis) with synchronization, pre-heating, and various eviction policies.
*   `cascade-lock`: Distributed locking mechanisms.
*   `cascade-bloom`: Distributed Bloom filter implementation.
*   `cascade-limiter`: Rate limiting capabilities.
*   `cascade-queue`: Distributed queues.
*   `cascade-pubsub`: Publish/Subscribe messaging.
*   `cascade-autoconfigure`: (Presumed) Spring Boot auto-configuration to automatically configure Cascade beans.
*   `cascade-spring-boot-starter`: A simple starter POM to include all necessary modules for a Spring Boot application.

The central entry point is the `cc.coderm.cascade.core.Cascade` class, which acts as a facade, providing builder methods to access all the different components (Cache, Lock, Bloom filter, etc.).

## Project Status

**Current State: Design & Skeleton**

Analysis of the project structure reveals that the framework is currently in a **design and skeleton phase**. While the overall architecture is well-defined in the root `pom.xml` and detailed design documents (`*-design.md`) exist for key modules like `cascade-cache` and `cascade-lock`, the actual Java source code for these features is largely absent.

Key observations:
*   The `cascade-core` module contains the main `Cascade.java` facade, providing an entry point.
*   Other modules, such as `cascade-lock`, `cascade-cache`, and `cascade-autoconfigure`, have empty source directories despite having comprehensive design documents.

This indicates that the project's intended functionality is clearly planned, but the implementation has not yet been completed. Future work on this project will likely involve implementing the features described in the design documents within their respective modules.

## Building and Running

The project is a standard Maven project.

**Build the entire framework:**
To build all modules and install them into your local Maven repository, run the following command from the project root directory:
```bash
mvn clean install
```
Note: The build will succeed, but it will produce mostly "empty" jars for the unimplemented modules.

**Running Tests:**
To run the unit tests for the entire project:
```bash
mvn test
```

## Development Conventions

*   **Unified Facade:** All framework features should be accessed through the `cc.coderm.cascade.core.Cascade` facade.
*   **Builder Pattern:** Components are constructed using a fluent builder pattern (e.g., `cascade.cache("myCache").build()`).
*   **Modularity:** Each distinct feature (cache, lock, etc.) is maintained in its own module (`cascade-*`).
*   **Spring Boot Integration:** The framework is designed to be seamlessly integrated into Spring Boot applications via the `cascade-spring-boot-starter`. The `cascade-autoconfigure` module should handle the automatic creation and configuration of the primary `Cascade` bean.
*   **Design-Driven Development:** Each major module is intended to have a `*-design.md` file that outlines its architecture and capabilities. Development should follow the specifications in these documents.
