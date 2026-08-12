# Contributing to Tesla Dashboard

[中文](wiki/zh/CONTRIBUTING_zh.md)

Thank you for your interest in contributing to Tesla Dashboard! This document provides guidelines and information for contributors.

## Code of Conduct

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before contributing.

## How to Contribute

### Reporting Bugs

1. Check [existing issues](https://github.com/TimHuang336699/tesla-dashboard/issues) to avoid duplicates
2. Create a new issue using the **Bug Report** template
3. Include as much detail as possible

### Suggesting Features

1. Check [existing issues](https://github.com/TimHuang336699/tesla-dashboard/issues) for similar suggestions
2. Create a new issue using the **Feature Request** template
3. Explain the use case and benefits

### Pull Requests

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`./gradlew test`)
5. Run lint (`./gradlew lint`)
6. Commit your changes (`git commit -m 'Add amazing feature'`)
7. Push to the branch (`git push origin feature/amazing-feature`)
8. Create a Pull Request

## Development Setup

### Prerequisites

- Android Studio Hedgehog or newer
- JDK 17+
- Android SDK 34

### Building

```bash
# Clone the repository
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew test

# Run lint
./gradlew lint
```

## Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add KDoc comments for public APIs
- Keep functions focused and small

## Commit Messages

- Use present tense ("Add feature" not "Added feature")
- Use imperative mood ("Move cursor to..." not "Moves cursor to...")
- Keep first line under 72 characters
- Reference issues and pull requests

## Testing

- Write unit tests for new business logic
- Write UI tests for new UI components
- Ensure all tests pass before submitting PR

## License

By contributing, you agree that your contributions will be licensed under the [GPL-3.0 License](LICENSE).

## Questions?

Feel free to open an issue for any questions about contributing.
