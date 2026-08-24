# CI PR verification

This PR exists only to verify the Trainer 1.1.0 pull-request workflow end-to-end. The validate job must run tests, build the demo dataset, execute one listwise epoch, and produce `best.pt` plus `calibration.json`.
