# Add GitHub Secrets for keystore signing
# Prerequisites: GitHub CLI (gh) installed and authenticated

# Encode keystore to base64
$keystorePath = "C:\Users\auges\temp\badger-android\badger.keystore"
$keystoreBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystorePath))

# Add secrets to GitHub
Write-Host "Adding keystore secrets to GitHub..."

# Add KEYSTORE_FILE (base64 encoded keystore)
gh secret set KEYSTORE_FILE --body $keystoreBase64 --repo augesrob/badger-android
Write-Host "✅ KEYSTORE_FILE added"

# Add KEYSTORE_PASSWORD
gh secret set KEYSTORE_PASSWORD --body "badger123" --repo augesrob/badger-android
Write-Host "✅ KEYSTORE_PASSWORD added"

# Add KEY_ALIAS
gh secret set KEY_ALIAS --body "badger" --repo augesrob/badger-android
Write-Host "✅ KEY_ALIAS added"

# Add KEY_PASSWORD
gh secret set KEY_PASSWORD --body "badger123" --repo augesrob/badger-android
Write-Host "✅ KEY_PASSWORD added"

Write-Host ""
Write-Host "🎉 All secrets added! Now update the CI workflow..."
