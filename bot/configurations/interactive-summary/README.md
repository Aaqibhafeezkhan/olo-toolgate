<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Interactive text summary

This bot accepts text from the Admin console Run panel and invokes one configured
summarization agent/model. It needs no hot folder, tool package, trigger package,
device approval or device files. With no trigger dispatch mode configured, manual
execution uses PREPARE and cannot perform tool mutations.

## Configure the model

`bot.json` contains `interactive-summary-provider`, its origin, model ID,
generation settings and destination vault secret name. The supplied local demo
uses the external TLS mock at `https://demo-provider:9443`, model `demo-b`, and
vault secret `demo-provider-key`, scoped to `default-tools`/`default-devices`.
That mock returns `demo-b: <input>` to prove routing; it does not perform semantic
summarization. Use the external fixture/vault setup in
[the debug runbook](../../../debug/profiled/README.md), or provide your own model
endpoint and secret. Model allowlists, CA trust and monitoring remain installation
settings outside the bot. No credentials are exported in the `.olo`.

## Build and run

From the repository root, with the debug Python selected as in the runbook:

```powershell
& $debugPython bot/build.py --configuration interactive-summary --version 1.0.0-demo.interactive
```

Import `bot/bin/interactive-summary-interactive-summary-1.0.0-demo.interactive.olo`
on **Bots**, then publish its prepared revision. Click **▶ Run**, paste this into
**Bot input**, and click **Run bot**:

```text
The database connection pool was exhausted. Restarting the pool restored service.
```

The same panel shows recorded queue, agent/model/node progress and the final
`summary` result. The mock result starts with `demo-b:` and contains the submitted
text. With a real model, the configured agent prompt produces the summary.
No input/output directories or folder permission commands are required. Results
remain in backend run storage and can also be read through the run-result API.
Closing the panel does not cancel an accepted run. To run another input, reopen
the Run panel; retry an unknown submission using **Check same submission**.
