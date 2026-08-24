# CI verification

This file documents the first pull-request CI verification for Trainer 1.1.0. The validate workflow must run unit tests, build a deterministic demo dataset, execute one listwise training epoch, and verify that both `best.pt` and `calibration.json` are produced.
