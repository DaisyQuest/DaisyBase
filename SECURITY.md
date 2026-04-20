# Security Policy

## Reporting a Vulnerability

If you believe you have found a security issue in DaisyBase, please report it privately to the maintainers rather than opening a public issue first.

Include:

- a clear description of the problem
- affected component or module
- reproduction steps or proof of concept
- impact assessment
- any suggested mitigation, if you have one

If you only have a suspicion and not a complete proof, that is still worth reporting. Clarity matters more than polish.

## Scope

Security-sensitive areas in this repository include:

- authentication and authorization behavior
- JDBC remote access
- server protocol framing and parsing
- installer download and packaging flows
- credential handling in config files and launchers
- crash recovery and persistence integrity when corrupted input is involved

## What to Expect

A good security response should do three things:

- acknowledge the report
- assess severity and scope
- either fix the issue or document why the report is out of scope

## Supported Lines

This project currently moves as a single active line.

That means:

- the `main` branch is the supported branch
- fixes are expected to land on current code rather than on long-lived maintenance branches

## Public Disclosure

Please wait for the maintainers to confirm remediation before public disclosure. If the issue requires coordinated messaging, the goal is to publish a fix and an explanation close together.
