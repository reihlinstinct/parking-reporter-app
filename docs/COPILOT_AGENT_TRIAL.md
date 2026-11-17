# Read-only custom Copilot agent trial

This experiment adds `.github/agents/repo-reviewer.agent.md`. It packages existing
CONTRIBUTING.md and SECURITY.md review rules, with particular attention to the
canonical-topic/draft-readiness integration boundary. It enables only read/search
aliases, not edit/execute or MCP tools. These restrictions reduce capability;
instructions alone are not a security sandbox or a merge approval.

## Free versus paid

Checked November 17, 2026 against GitHub's current documentation:

- Creating a profile file does not buy a subscription or start a cloud task.
- Copilot CLI is documented for all Copilot plans, including Free with its limited
  included allowance. Do not enroll in a paid plan or use paid overage for this trial.
- GitHub's cloud agent documentation says cloud agent execution requires a paid
  Copilot plan. A repository profile does not unlock that entitlement.
- A policy toggle saying Enabled does not prove a cloud task is included in Free.

Sources:
- https://docs.github.com/en/copilot/get-started/plans
- https://docs.github.com/en/copilot/how-tos/copilot-cli/use-copilot-cli/invoke-custom-agents
- https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/create-custom-agents-for-cli
- https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/create-custom-agents

## Bounded trial

In a public-source-only checkout, with a Copilot Free account authenticated in an
installed Copilot CLI, select `/agent` then `repo-reviewer`, or use:

```sh
copilot --agent repo-reviewer --prompt "Read the topic identity and draft readiness source. Explain whether raw numeric subjects pass readiness. Do not edit, execute commands, use external tools or submit anything. Cite files and state limits."
```

Use default/auto model selection. Do not enable all tools, add MCP servers, supply
private evidence or secrets, grant blanket permissions or dispatch any workflow.
Stop if it requests payment, upgrade or unavailable entitlement. A profile being
parsed is not proof the model followed it or produced a useful review.

## Observed smoke test

On November 17, 2026, the official Copilot CLI 1.0.112 accepted this profile via
`--agent repo-reviewer` on an authenticated Copilot Free account. The single prompt
completed with exit 0 in about 50 seconds using auto model selection. The CLI
reported 1.21 AI credits consumed from the included allowance, not a dollar cost.
No paid plan, card, overage opt-in or GitHub.com cloud-agent task was used.

The CLI ran against an exported public-source-only tree, without Git metadata,
private evidence or repository credentials. Authentication used a dedicated
fine-grained token limited to Copilot Requests with public read-only repository
access. Built-in MCP servers were disabled, shell/write/URL requests were denied,
and general temporary-directory access and account session sync were disabled.
Post-run SHA-256 comparison of every tracked file found no changes or extra files.

The answer correctly traced raw numeric subject rejection through TopicIdentity,
ReviewDraft.isReady and SubmissionCoordinator, distinguished draft identities from
numeric payload codes, and cited the actual EngineTrialTopicsTest regression.
It also called out the missing picker-to-ready-draft proof and said tests/live
submission were not executed. The maintainer checked those statements against the
source. This establishes basic profile invocation and useful source inspection,
not effectiveness at finding unknown bugs or a qualifying independent PR review.

CLI inference still sends prompts and selected public source to GitHub-hosted
models. Running the CLI locally is not offline/local-model inference. No private
source or evidence is authorized by this example. Pricing and allowances can
change; recheck entitlement before repeating the trial.

## Three-PR review comparison

The same read-only profile was tested against frozen public base/head trees and a
complete diff. Peer findings were withheld from Copilot until after its verdict.
Shell, file-write, URL and built-in MCP access remained disabled. No verdict was
posted as a formal GitHub approval.

| PR and reviewed head | Copilot result | Maintainer check |
| --- | --- | --- |
| #568 `ecece88` | PASS, both changed files read | No actionable issue found; runtime/pricing claims correctly treated as reported, not independently verified |
| #557 `a8690c0` | FINDINGS, eight changed files read | Both reproduced: zero-test unittest execution can pass; an interrupted output write leaves a partial final manifest that blocks retry |
| #559 `6c08be2` | PASS, twenty changed files read | Inconsistent: includes the same manifest writer but did not flag its partial-output defect |

The two larger reviews reached the first process time budget. They were resumed
once to report from the source already read, rather than silently presenting an
unfinished run as complete. Each verdict states source-inspection limits and no
executed-test or CI proof. Reported review-session usage was about 0.48, 2.98 and
3.32 included AI credits respectively, with automatic model selection.

GitHub's Gemini reviews on these heads were capped at the first 12,000 diff
characters. Its #557 note affirmed the private-output warning, and its #559 note
concerned comment wording rather than a code defect. Its earlier #568 date warning
was rejected because November 17, 2026 was the actual check date. These limited
reviews do not establish that another full-source reviewer missed the bugs.

The reproduced defects are fixed separately in #557 and inherited into #559,
with an atomic no-replace private output publication and a test runner that fails
on zero tests. Findings from the old heads do not approve the updated heads.

Assessment: useful as an additional source reviewer, with demonstrated small
findings and demonstrated inconsistency. It does not replace the established
independent exact-head review plus green CI gate. This trial does not prove strong
unknown-bug detection, adversarial robustness or full coverage from claimed file
counts alone.
