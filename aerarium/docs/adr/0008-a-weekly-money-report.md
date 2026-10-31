---
title: A weekly money report instead of a monthly digest
status: accepted
date: 2026-10-31
updated: 2026-10-31
---

# 0008 · A weekly money report

## Context

Dewi wanted the weekly run to also say how his money is doing: "over the last week … interest, or money lost
… in context of the last year". The plan had a monthly digest on the 1st.

## Decision

- Every Monday Aerarium computes a report for the previous week and keeps it (a Reports page with every past
  week) and sends a short Matrix message with the headline and a link.
- Contents, all computed exactly from stored data:
  - **Where the change came from**: net worth change split into saved (money in minus money out, excluding
    transfers between his own accounts), markets (investment value change minus net deposits), interest
    earned, and debt paid down.
  - **Spending vs usual**: the week against the 12-month weekly average, naming the bills and categories that
    moved it.
  - **Year view**: a 52-week chart, year-to-date change, the same week last year once history exists, a
    12-month projection at the current rate, and the savings rate.
  - **Tax-aware interest**: interest earned outside ISAs this tax year against the Personal Savings Allowance.
- From phase 2, the Monday Sonnet run (the deal scout's container) adds 3-5 sentences of context. It sees the
  report's totals and changes only.
- A tax-year review follows each 5 April (ISA allowance used, interest against the allowance, pension
  contributions, net worth over the year). The monthly digest is dropped; the weekly report covers it.

## Consequences

A report is only as good as its attribution, so transfers between Dewi's own accounts must be recognised;
until they are, the report says which movements it could not classify instead of guessing.
