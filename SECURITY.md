# Security Policy

[中文](SECURITY_zh.md)

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.5.x   | :white_check_mark: |
| < 0.5   | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability within Tesla Dashboard, please send an email to TimHuang336699@users.noreply.github.com. All security vulnerabilities will be promptly addressed.

**Please do not report security vulnerabilities through public GitHub issues.**

### What to include

When reporting a vulnerability, please include:

- Description of the vulnerability
- Steps to reproduce the issue
- Potential impact
- Suggested fix (if any)

### Response timeline

- **Acknowledgment**: Within 48 hours
- **Initial assessment**: Within 1 week
- **Fix release**: Depends on severity

## Security Best Practices

### For Users

- Keep your app updated to the latest version
- Only pair with your own vehicle
- Don't share your private keys
- Unpair before selling your device

### For Developers

- Never commit API keys or secrets
- Use Android Keystore for sensitive data
- Follow OWASP Mobile Security guidelines
- Run security audits regularly
