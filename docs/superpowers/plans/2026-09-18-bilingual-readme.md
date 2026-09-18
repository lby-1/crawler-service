# Bilingual README Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish matching Chinese and English GitHub landing pages that present the crawler service accurately, attractively, and safely.

**Architecture:** Keep `README.md` as the Chinese default and `README_EN.md` as the English counterpart, with reciprocal language links at the top. Derive every capability, command, endpoint, source site, and configuration key from the current repository; use one Mermaid diagram to explain the external-to-internal data flow.

**Tech Stack:** Markdown, Mermaid, Shields.io badges, Spring Boot 3.1.4, Java 17, Quartz, Playwright, MyBatis-Plus, MySQL, DM8

---

### Task 1: Chinese landing page

**Files:**
- Create: `README.md`
- Reference: `pom.xml`
- Reference: `src/main/resources/application.yml`
- Reference: `src/main/resources/application-mysql.yml`
- Reference: `src/main/java/com/jpwise/crawler/controller/CrawlController.java`
- Reference: `src/main/java/com/jpwise/crawler/controller/ResultController.java`

- [ ] **Step 1: Create the Chinese README structure**

Write `README.md` with the language switch, project value proposition, repository-backed technology badges, capability table, configured-source table, Mermaid data-flow diagram, quick start, environment-variable table, REST API table and examples, deployment notes, project layout, extension guide, limitations, contribution guidance, and license.

The capability wording must explicitly cover official bidding sources, detail extraction, authorized Jianyu competitor data, MySQL and DM8, Quartz schedules, and the `synced=0` pending-result plus confirmation workflow.

- [ ] **Step 2: Verify Chinese facts against source**

Run:

```powershell
rg -n '/api/(health|crawl|results)|MYSQL_DB|DM_DB|EXTERNAL_BUSINESS|CRAWLER_AUTH|CHINABIDDING|jianyu|Quartz|Playwright' README.md src/main pom.xml run.sh
```

Expected: every documented endpoint, environment variable, and technology has a matching repository source; README examples contain placeholders only.

### Task 2: English counterpart

**Files:**
- Create: `README_EN.md`
- Reference: `README.md`

- [ ] **Step 1: Write idiomatic English copy with section parity**

Write `README_EN.md` with the same facts and section order as `README.md`. Translate intent rather than syntax, preserve exact commands and identifiers, and link `README.md` and `README_EN.md` reciprocally at the top.

- [ ] **Step 2: Compare section coverage**

Run:

```powershell
rg '^## ' README.md
rg '^## ' README_EN.md
```

Expected: both files contain matching conceptual sections in the same order.

### Task 3: Documentation verification and delivery

**Files:**
- Verify: `README.md`
- Verify: `README_EN.md`

- [ ] **Step 1: Check links, whitespace, and credential safety**

Run:

```powershell
git diff --check
rg -n 'README(_EN)?\.md|LICENSE|src/main/resources|deploy/' README.md README_EN.md
rg -n --pcre2 '(BEGIN .*PRIVATE KEY|gh[pousr]_[A-Za-z0-9_]+|password\s*[:=]\s*(?!\$\{|<|your_)[^\s]+)' README.md README_EN.md
```

Expected: no whitespace errors, every relative link resolves, and the credential scan prints no matches containing real values.

- [ ] **Step 2: Commit and push**

```powershell
git add README.md README_EN.md docs/superpowers/plans/2026-09-18-bilingual-readme.md
git commit -m "Make the crawler service discoverable to Chinese and English users"
git push origin main
```

Expected: the remote `main` branch advances to the documentation commit and `git status --short --branch` is clean.
