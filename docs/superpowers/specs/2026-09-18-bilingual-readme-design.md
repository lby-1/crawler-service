# Bilingual README Design

## Goal

Create an attractive, factual GitHub landing page for the crawler service in Chinese and English. The documentation must highlight capabilities that are present in the repository without exposing credentials or promising permanent compatibility with third-party websites.

## Files and navigation

- `README.md` is the default Simplified Chinese landing page.
- `README_EN.md` is the English version.
- Both files begin with reciprocal `简体中文 | English` links so readers can switch languages without an intermediate landing page.
- Both versions use the same section order and describe the same verified capabilities.

## Content structure

1. Project title, concise value proposition, and factual technology badges.
2. Core capabilities: official bidding-site collection, detail extraction, Jianyu competitor data, MySQL and DM support, scheduled jobs, and external-to-internal network synchronization.
3. Configured sources and announcement categories derived from `application.yml`.
4. Mermaid architecture flow from public sources through the crawler and external database to an internal server.
5. Quick start with Java 17, Maven, database initialization, required environment variables, startup, and health check.
6. Safe configuration examples for databases, API authentication, Jianyu, proxy access, and Quartz schedules.
7. Main REST API endpoints for collection, details, status, results, and synchronization acknowledgement.
8. Deployment options, project layout, extension guidance, contribution guidance, limitations, and license.

## Accuracy and safety rules

- Describe the project as verified against configured official bidding sources, while warning that upstream layout, anti-bot, and access-policy changes can require handler updates.
- Do not claim CI, test coverage, throughput, or universal website compatibility when the repository does not prove those claims.
- Do not include real passwords, tokens, proxy credentials, private endpoints, or database addresses.
- Use environment-variable placeholders in every example.
- State that Jianyu functionality requires the user's own authorized API configuration.
- State that users are responsible for complying with source-site terms, robots policies, rate limits, and applicable law.

## Presentation

- Use concise headings, feature tables, code examples, and a single Mermaid workflow diagram.
- Use Shields.io badges only for repository-verifiable technologies and the existing license.
- Avoid decorative screenshots or unsupported status badges.
- Keep the Chinese copy natural and direct; write the English version idiomatically rather than translating word for word.

## Verification

- Compare the two README files for section and fact parity.
- Verify all relative links and referenced repository paths.
- Check command and endpoint examples against source code and deployment scripts.
- Scan new documentation for credential-like values.
- Run Markdown-oriented static checks where locally available; do not run Maven or frontend packaging per repository instruction.
