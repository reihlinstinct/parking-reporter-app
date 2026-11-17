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

Acceptance needs separate evidence for profile parsing/discovery, a free-account
invocation and a useful source-grounded answer. Until those are actually observed,
runtime compatibility and usefulness remain unverified. This file alone claims
neither successful execution nor an independent review.
