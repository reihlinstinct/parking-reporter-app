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
