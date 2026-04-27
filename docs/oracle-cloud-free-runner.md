# Oracle Cloud Free Runner

Use this when you want `GitHub Actions` builds on an Oracle VM instead of a local machine.

## What This Gives You

- free self-hosted GitHub runner on Oracle Cloud
- Android SDK + NDK installed for Storyline
- low-RAM-safe defaults: swap enabled, Gradle workers limited
- same `Android CI Build` workflow keeps working through `CI_RUNNER_LABELS`

## Recommended Shape

- use an Oracle Linux or Ubuntu `x86_64` VM for this script
- this repo's Android build is heavy; smaller free shapes will be slow
- if Oracle only offers an ARM free VM in your region, do not switch `CI_RUNNER_LABELS` yet

## Bootstrap

On the Oracle VM:

```bash
git clone https://github.com/sarojshahu12-max/storyline.git
cd storyline
sudo GH_RUNNER_URL="https://github.com/sarojshahu12-max/storyline" \
  GH_RUNNER_TOKEN="PASTE_RUNNER_REGISTRATION_TOKEN" \
  RUNNER_NAME="oracle-free-01" \
  RUNNER_LABELS="self-hosted,Linux,X64,storyline,oracle-free" \
  .github/scripts/bootstrap-oracle-free-runner.sh
```

The registration token comes from GitHub:
- `Repo Settings -> Actions -> Runners -> New self-hosted runner`

## Turn It On

Only after the runner shows `online`, point the workflows to it:

```bash
gh variable set CI_RUNNER_LABELS \
  --repo sarojshahu12-max/storyline \
  --body '["self-hosted","Linux","X64","storyline","oracle-free"]'
```

## Verify

Run this on the VM:

```bash
.github/scripts/oracle-runner-doctor.sh
```

Then dispatch:
- `Android CI Build`
- `Firebase Test Lab`
- `Google Cloud Android CI`

## Roll Back

If the Oracle runner is unstable, switch workflows back:

```bash
gh variable set CI_RUNNER_LABELS \
  --repo sarojshahu12-max/storyline \
  --body '["ubuntu-latest"]'
```
