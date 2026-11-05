# Feature: <feature-name>

Use this template only when a written feature spec is useful. Omit irrelevant
sections and link existing material rather than duplicating it.

## Goal and Scope

Describe the outcome, included work, explicit exclusions, and constraints.

## Relevant Existing Context

Reference affected components, established patterns, and reusable helpers.
Record only context needed to understand the decisions.

### Tech Stack

- Languages and frameworks relevant to the task
- Build and runtime requirements

### Relevant Existing Services

- Affected components and their responsibilities

### Patterns to Follow

- Architecture, naming, error handling, and testing conventions

### Reusable Components

- Existing helpers, middleware, types, and contracts

### Security Baseline

- Actual controls and documented scope limits, when relevant

## Behavior and Contracts

Describe required behavior and compatibility expectations. Link the HTTP or
message contract and update it before implementation when it changes.

### Functional Requirements

- FR1: <Required behavior, or a user story when useful>

### Non-Functional Requirements

- <Applicable performance, reliability, security, or compatibility constraint>

## Acceptance Criteria

- [ ] <Observable, testable outcome>

## Consequential Design Decisions

Record choices, rationale, and material tradeoffs where design work is needed.
Do not add abstractions merely to populate this section.

## Security Considerations

Include only when security-relevant. Describe affected trust boundaries,
concrete threats, and required controls.

## Verification

Identify relevant checks and risks, including real dependency requirements.
Separate unit, integration, or manual verification only where useful.
Do not invent coverage targets or tooling requirements.

### Cross-Cutting Scenarios

- <Genuinely shared behavior, its expected outcome, and reference test>

### Integration Test Scenarios

#### Operation: <operation-name>

- <Happy path and relevant validation, failure, or asynchronous behavior>

### Unit Test Strategy

| Class | Key Scenarios |
| --- | --- |
| <Actual test class> | <Local behavior, boundaries, and error paths> |

### Test Infrastructure

- Existing harnesses, fixtures, mocks, real dependencies, and test configuration

## Open Decisions

List unresolved choices, their impact, and who can resolve them. Keep execution
progress in optional coordinator-owned state rather than duplicating it here.
