# LibreDeX Development & Release Maintenance

## Repository layout

- Public main repository: `KanzakiK/libredex-public`
- Local workspace: `D:\dex_work`, remote `origin` points to the public repo
- Private optional transport overlay: kept outside the public tree; see the
  private maintenance checklist under `docs\repo-maintenance.md` for the exact
  path and provider class
- Public mirror worktree: `D:\dex_work\public`
- Backups: `D:\dex_work\.backup`
- Archived old git metadata: `D:\dex_work\.git-private-archive`

## Normal push

1. Bump the version first:
   - `scripts\build.ps1` default `-VersionName` / `-VersionCode`
   - `app\build.gradle` fallback values
   - `README.md` release line
   - `CHANGELOG.md`
2. Commit and push normally:

```powershell
git add -A
git commit -m "fix(feature): ..."
git push origin master
```

Do not force push unless explicitly required. The public history is a clean
snapshot history plus appended release commits.

## Public release packaging

1. Confirm the version was bumped.
2. Load the local signing environment (see `docs\repo-maintenance.md`).
3. Build the signed release:

```powershell
& .\scripts\build.ps1 -Configuration Release
```

4. Replace `libredex-public-release.apk` with the built artifact.
5. Run the private maintenance checklist's scan before publishing.
6. Commit, push, and create the GitHub Release with the signed APK.

## Full build with the private optional transport overlay

Exact module path and provider class are intentionally kept in the private
maintenance checklist. In this workspace, read
`docs\repo-maintenance.md` before building the full variant.

## Public content rules

- Keep changelog and release notes generic.
- Do not advertise which transports are omitted from the public build.
- Keep internal directories and private overlay metadata out of the public
  repository.

## Rollback

Local backups and the archived old git metadata are described in
`docs\repo-maintenance.md`. Always verify bundles with
`git bundle verify` before restoring.
