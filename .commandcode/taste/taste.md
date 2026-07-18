# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# workflow
- After pushing version bumps or release commits to CI-enabled repos, monitor the GitHub Actions build to verify it passes successfully. Confidence: 0.75
- After CI build results come in (success/failure), notify the result using ntfy topics configured in `.dotfiles/profile.md`. Confidence: 0.85
- Name release APK files as `{project}-{version}.apk` format. Confidence: 0.75
- Use imperative commit title, blank line, then bulleted body with `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>` footer for project commits. Confidence: 0.70
- Update `versionName` in `build.gradle.kts` to match the release tag before creating a new release. Confidence: 0.75
