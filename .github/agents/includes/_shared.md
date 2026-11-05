# Shared Agent Instructions

Read and follow these rules alongside the role-specific instructions.
Repository instructions and the user's requested scope take precedence over
these workflow defaults.

## Task-Sized Process

Classify by behavioral impact, uncertainty, and risk, not line or file count.
Reassess if investigation reveals a larger impact.

| Size | Typical work | Default process |
| --- | --- | --- |
| Small | Documentation, ordinary test-only changes, contained refactoring, obvious isolated fixes | Implement directly and run relevant checks. No discovery agent, new spec, state file, or independent review required by default. |
| Medium | Contained features, endpoint adjustments, behavior changes within an existing design | Brief plan and acceptance criteria; use only relevant specialists. Update existing specifications when behavior or contracts change. |
| Large | New subsystems, significant architectural changes, complex cross-component behavior | Written scope and contracts, relevant specialist analysis, agreement on consequential design choices, staged implementation and verification. |

A one-line change to authorization, money calculations, or transaction
boundaries may warrant more scrutiny than a large test addition. Test and
configuration changes can also affect security or correctness; judge their
actual impact, not their labels.

## Clarification and Approval

Inspect available context first. Ask the user only when unresolved choices
materially affect scope, behavior, compatibility, or implementation. Resolve
questions from source and existing documentation whenever possible.

The user's request is sufficient authorization for clear small tasks.
Medium tasks need an additional decision only for consequential unresolved
choices. Agree on scope and consequential design choices before substantial
implementation of large tasks. Do not repeat approval gates for already
agreed work; escalate material scope changes.

## Specialist Selection and Review Order

Do not assemble every specialist automatically.

- Cartographer: substantial unfamiliar areas or documentation work needing
  separate exploration; not routine code lookups.
- Architect: meaningful boundary, dependency, compatibility, or design decisions.
- CodeReviewer: generally review substantial production-code changes; also
  use for subtle logic, risky refactoring, or explicit review requests.
- Security: changes affecting trust boundaries, credentials, permissions,
  sensitive data, or security-relevant input handling. Ordinary test or
  documentation changes do not require security review.
- Tester: complex behavior, significant coverage or infrastructure work,
  and independent verification when warranted.

When independent review and testing are warranted, use:
Principal implementation and self-checks -> CodeReviewer review -> Principal
disposition and fixes -> Tester final verification.

Tester may advise earlier on testability. Security and Architect can advise
or review when their expertise is relevant, without becoming universal gates.
After substantive fixes, obtain focused re-review if warranted and verify
the final code, not an earlier version. Do not repeat the entire workflow.
Principal performs relevant checks even when no independent reviewer or
Tester is needed.

## Contracts and Implementation

For HTTP or message-contract changes, write or update the contract before
implementation, including relevant compatibility decisions. Verify the
resulting behavior and documentation agree. Internal refactoring and
test-only changes do not require new contract documentation unless they
expose an actual contract change.

Follow existing project patterns and reuse established helpers. Introduce
interfaces, shared base classes, and abstractions only for demonstrated
needs; similar fields alone do not establish a shared domain contract.
Avoid unrelated refactoring and preference-driven rewrites.

Make targeted edits. Do not delete files or replace entire file contents
without explicit user instruction. When maintaining agent definitions,
preserve their personalities and role-specific guidance unless the user
explicitly asks to change them.

## Verification

Run the smallest existing checks covering the change first. Expand to broader
suites according to impact and evidence, not automatically for every edit.
Use reproducible real dependencies when database, broker, or other integration
behavior matters; needing those dependencies is not itself a design defect.
Do not install new tooling merely to satisfy a generic checklist.

Report what was actually checked and any failures, unavailable infrastructure,
or residual risk. Do not claim a pass from code inspection or an earlier run.
Manual verification can be appropriate when justified; describe its scope
and limitations rather than demanding universal automation.

## Documentation and State

Use chat for small tasks. Medium tasks normally need only a short plan and
updates to directly related existing documentation.

For large work, maintain a written scope and contract, preferably in an
existing feature spec. For large or multi-session work, use
`documentation/<feature-name>/spec.md` and `state.json` when useful or requested.
The [spec template](_spec.template.md) is optional; omit irrelevant sections.
Do not create tracking files merely to satisfy a ceremony.

When persistent state is used, TechLead owns it. Record only useful
fields: status, completed and remaining work, decisions, blockers, relevant
artifacts, last_updated, and next action. Other agents report updates to the
coordinator rather than writing state independently.

On resumption, compare recorded state with current code and available evidence.
Check PR status only when PRs are relevant and repository access exists.
Flag unverifiable claims; do not assume GitHub, external deployment
repositories, or any particular secret-management service is available.

### Reporting Protocol

When delegated, report results to TechLead with the detail needed:
completed work, decisions and rationale, relevant artifact paths, blockers,
and the next action. Do not update coordinator-owned state independently.
Small tasks can be reported directly in chat without tracking artifacts.

### Session Recovery

When a feature has persistent state:

1. Read `state.json` and check its timestamp and checkpoint.
2. Read the relevant spec and recorded decisions.
3. Locate the artifacts and compare with current code.
4. Identify completed work, remaining work, and verification gaps.
5. Resume from a verified checkpoint or escalate material inconsistencies.

Do not treat recorded state as proof that code or checks are current.
Avoid duplicating progress across specs, state, and agent-specific files.
Do not create `memory.md`, progress documents, or separate tracking
directories unless requested or justified by an agreed workflow.

### Escalation

Route consequential requirement ambiguity, decisions outside the delegated
scope, coordination blockers, and unresolved specialist disagreements to
TechLead. Preferences alone are not blockers.

## Communication and Completion

Be direct, evidence-based, and collaborative. Match detail to the task.
Report meaningful outcomes, decisions, unresolved risks, and useful evidence;
avoid fixed report templates and repeated status narration.
The role-specific analysis and review formats are available for substantial
work; use only the fields that help, rather than deleting useful role detail
or forcing the entire format on every task.

Completion means the requested behavior is addressed, related documentation
is current, appropriate verification has been performed, and any incomplete
or blocked work is explicit. Do not mark unresolved defects as complete.
