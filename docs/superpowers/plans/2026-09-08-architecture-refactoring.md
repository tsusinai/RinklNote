# 架构重构计划 — 2026-09-08

> **For Claude:** Use SDD to implement this plan task-by-task.

**Goal:** 修复已识别的架构问题，提升代码可维护性与类型安全，不改变运行时行为。

**Architecture:** 字符串枚举化 → 拆 Repository → 去 ViewModel 耦合 → 引入 Domain 层。全部为安全重构，不改变功能。

**Tech Stack:** Kotlin 2.0, Room 2.6, Jetpack Compose, MVVM

---

## Task 1: 字符串枚举化（安全重构，无行为变更）

**Files:**
- Add: app/src/main/java/com/example/rinklnote/domain/BillType.kt
- Add: app/src/main/java/com/example/rinklnote/domain/Source.kt
- Add: app/src/main/java/com/example/rinklnote/domain/MessageKind.kt
- Modify: app/src/main/java/com/example/rinklnote/data/db/entity/Bill.kt
- Modify: app/src/main/java/com/example/rinklnote/data/db/entity/Category.kt
- Modify: app/src/main/java/com/example/rinklnote/data/db/entity/ChatMessage.kt
- Modify: app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt
- Modify: ~30 files referencing EXPENSE/INCOME/APP/QQ/kind strings

## Task 2: QuickAddViewModel.reset() 改用 copy 模式

**Files:**
- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt

## Task 3: 清理遗留注释代码 + 更新 AGENTS.md

**Files:**
- Modify: app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
- Modify: AGENTS.md

## Task 4: 拆分 God Repository（接口拆分）

**Files:**
- Add: app/src/main/java/com/example/rinklnote/data/repository/AccountRepository.kt
- Add: app/src/main/java/com/example/rinklnote/data/repository/BudgetRepository.kt
- Add: app/src/main/java/com/example/rinklnote/data/repository/ChatRepository.kt
- Modify: app/src/main/java/com/example/rinklnote/data/repository/BillRepository.kt
- Modify: app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt
- Modify: ~8 files referencing renamed types

## Task 5: 解耦 ViewModel（AiViewModel 去掉 QuickAddViewModel 引用）

**Files:**
- Add: app/src/main/java/com/example/rinklnote/navigation/BookingOrchestrator.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/AiViewModel.kt
- Modify: app/src/main/java/com/example/rinklnote/ui/viewmodel/QuickAddViewModel.kt
- Modify: app/src/main/java/com/example/rinklnote/navigation/AppNavigation.kt
