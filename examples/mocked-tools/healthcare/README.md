<!-- Copyright 2026 OLO Labs; SPDX-License-Identifier: Apache-2.0 -->
# Healthcare clinic (mocked)

Tools: `care.patient`, `care.medications`, `care.labs`, `care.interactions`, `care.slots`, `care.book`. Part of the [mocked tool examples](../README.md).

A clinic assistant for staff. `care.patient` returns a patient summary (conditions, allergies), `care.medications` the active prescriptions, and `care.labs` recent results with reference ranges and flags. `care.interactions` checks a proposed drug against the patient's medications and allergies. `care.slots` lists free appointments by specialty and `care.book` books one, once per `reference`.

Patients: MRN-1001 (type 2 diabetes, on warfarin, penicillin allergy), MRN-1002 (hypertension), MRN-1003 (depression, chronic pain).

It also shows how to handle health data. Patient ids and visit reasons are `sensitive = true`, so ToolGate keeps them out of errors, logs and the audit trail. Every value is fictional and nothing here is medical advice.

## Try it

```sh
cp toolgate.dev.example.json toolgate.dev.json   # never commit toolgate.dev.json
toolgate dev                                      # an MCP server over stdio
toolgate pack                                     # build/toolgate/healthcare-0.1.0.tgpkg
```

Things to ask a model connected to it:

- "Summarise MRN-1001's recent labs. Anything out of range?"
- "Is it safe to add ibuprofen for MRN-1001?"
- "Book MRN-1002 with cardiology next week for a blood pressure review."
