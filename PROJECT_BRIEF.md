# Project Brief — Native FOSS Android Email Client

## Your mission

You are **Claude Opus 5.5**, acting as the technical lead, product lead, and primary coordinator of a new open-source Android application project.

You are working through **Herdr** alongside two other AI engineering agents:

- **Codex — GPT-6.1 Sol Medium**
- **OpenCode — Qwen3.8 27B (locally hosted)**

Your mission is to collaboratively design, develop, test, and refine a beautiful, fully native Android email client that can realistically replace applications such as Gmail, Samsung Email, or Thunderbird Android.

This application will be the first in a planned family of free and open-source Android applications, including Messages, Phone, Calendar, Contacts, Files, Gallery, Notes, Clock, Calculator, Browser, Keyboard, and others.

The goal is not to create another generic open-source application.

**The goal is to deliver an exceptional Android experience, combining premium design, reliable functionality, privacy, and complete user ownership.**

You have broad autonomy over engineering decisions, architecture, development strategy, tooling, and project organization.

I expect you to work collaboratively with Codex and Qwen, make decisions independently, and produce working software rather than stopping at planning or mockups.

---

## 1. Mandatory requirement: 100% native Android

This is a non-negotiable requirement.

**The application MUST be developed natively for Android, primarily using Kotlin and Jetpack Compose.**

Do not use:

- Flutter
- React Native
- Ionic
- Capacitor
- Electron
- Progressive Web Apps
- WebView-based application interfaces
- Any other cross-platform UI framework

The application must use Android's native APIs and integrate naturally with the operating system.

Expected native capabilities include:

- Jetpack Compose UI.
- Native Android navigation and system integrations.
- Edge-to-edge rendering.
- Predictive back gestures.
- Native notifications and notification actions.
- Reliable background operations.
- Proper Android lifecycle management.
- Native text selection, copy/paste, and sharing.
- Biometric authentication where appropriate.
- Android autofill and credential management where applicable.
- Native file pickers and document providers.
- Responsive gestures, scrolling, and animations.
- Support for modern Android accessibility features.
- Proper system dark/light theme integration.

WebView may be used only for specific technical requirements, such as securely displaying HTML emails. It must never serve as the foundation of the application interface.

Native Android performance, behavior, and platform integration take precedence over cross-platform compatibility.

**The application must feel like a first-class Android application, not a website packaged as an APK.**

Kotlin Multiplatform libraries may be considered for non-UI components if genuinely useful, but the Android interface itself must remain native.

---

## 2. Product vision

Create a premium-quality, privacy-first, fully FOSS email client.

The application must combine four essential qualities:

**Exceptional design**

A carefully crafted interface inspired by the minimalist visual language of ChatGPT and Grok.

**Reliable functionality**

A complete email client that users can trust with their daily communications.

**Privacy and independence**

No advertisements, unnecessary telemetry, vendor lock-in, mandatory cloud accounts, or reliance on proprietary backend services.

**Long-term maintainability**

An architecture and codebase suitable for a serious, sustainable open-source project.

The application should feel polished enough to compete with the best commercial Android applications while preserving the freedoms expected from open-source software.

It should not require the user to operate any custom backend.

A standard email account should be sufficient to start using it.

---

## 3. Design direction — Quiet UI

**The visual design is one of the highest priorities of this project.**

The primary visual references are the modern Android applications of **ChatGPT and Grok**.

The desired design language can be described as:

- Quiet UI
- Refined Minimalism
- Premium Minimalism
- Editorial Minimalism

These labels describe the intended aesthetic, not a requirement to follow an existing design system.

### Visual principles

Aim for:

- Calm, elegant, contemporary interfaces.
- Excellent typography and visual hierarchy.
- Carefully balanced spacing and proportions.
- Near-black, graphite, charcoal, and neutral surfaces.
- Beautifully designed light and dark themes.
- Subtle borders and restrained visual separation.
- Thoughtful use of rounded corners.
- Minimal, carefully selected accent colors.
- Elegant iconography with consistent visual weight.
- Excellent readability and contrast.
- Clean layouts that prioritize content.
- Thoughtful empty states, loading states, and transitions.
- Minimal visual noise.

The interface should feel sophisticated without looking overly decorative.

### Avoid

Do not create a stereotypical Material Design application.

Specifically avoid:

- Excessive colorful cards.
- Oversized pill-shaped components everywhere.
- Bright Material You colors dominating the interface.
- Unnecessary gradients and glassmorphism.
- Heavy shadows and excessive elevation.
- Overly dense toolbars.
- Cluttered dashboards.
- Decorative elements without functional purpose.
- Generic AI-generated interface patterns.

Material 3 libraries may be used as technical foundations, but the final design language must be custom and recognizable.

**Use Jetpack Compose to create our own visual identity rather than reproducing Google's default component styling.**

### Interaction design

The experience should emphasize:

- Comfortable one-handed operation.
- Natural gestures.
- Excellent touch responsiveness.
- Smooth, purposeful animations.
- Subtle haptic feedback.
- Elegant transitions between screens.
- Consistent contextual actions.
- Clear and predictable navigation.
- Minimal interaction friction.

Animations should enhance usability without slowing down the interface.

Avoid flashy effects that distract from the content.

### Design consistency

This application is the beginning of a broader Android suite.

Its typography, spacing, icons, surfaces, animation principles, and reusable components should establish a foundation that can eventually be reused across other applications.

However, do not overengineer the first application for hypothetical future requirements.

The immediate priority is to produce an excellent email client.

---

## 4. Core email functionality

The application must be a real, usable email client, not an interactive mockup.

It should implement the capabilities expected from a mature Android email application.

### Account management

- Multiple email accounts.
- IMAP support.
- SMTP support.
- Secure TLS connections.
- Modern authentication mechanisms.
- OAuth2 for providers that support or require it.
- Application-specific passwords where applicable.
- Automatic account configuration and discovery when possible.
- Manual server configuration.
- Multiple identities and sender aliases.
- Account-specific preferences.
- Unified inbox.
- Easy switching between accounts.

Compatibility with standard mail providers is essential, including Infomaniak, Gmail, Outlook, and privately hosted IMAP/SMTP servers.

The application must not depend on a proprietary email backend.

### Inbox and message management

- Conversation threading.
- Message grouping and chronological views.
- Read and unread states.
- Stars and flags.
- Archive.
- Delete and trash.
- Spam handling.
- Folder management.
- Move and copy messages.
- Bulk operations.
- Configurable swipe actions.
- Message searching.
- Account-specific and unified searches.
- Message filtering and sorting.
- Attachment indicators.
- Contact avatars where available.
- Clear handling of unread and important messages.

### Reading emails

Provide a beautifully designed reading experience.

Support:

- Plain-text messages.
- HTML messages.
- MIME multipart messages.
- Inline images.
- Attachments.
- Quoted replies.
- Conversation history.
- Sender and recipient details.
- Multiple recipients.
- Message metadata when requested.
- Convenient navigation between messages.
- Safe handling of external content.

The reading experience should be exceptionally comfortable, particularly for long conversations.

### Composing messages

Support:

- New messages.
- Reply.
- Reply all.
- Forward.
- Draft saving.
- Automatic draft recovery.
- Attachments.
- Inline images where feasible.
- Rich text composition.
- Plain-text composition.
- Multiple signatures.
- Multiple sender identities.
- Contact autocomplete.
- Recipient validation.
- Scheduled sending where technically feasible.
- Undo send where appropriate.

Composition should be intuitive, fast, and reliable.

### Synchronization

Reliable background synchronization is critical.

The application should handle:

- Multiple accounts.
- Large mailboxes.
- Incremental synchronization.
- IMAP IDLE where appropriate.
- Offline message caching.
- Queued operations.
- Network interruptions.
- Retry and recovery behavior.
- Battery and background execution constraints.
- Notifications for incoming messages.
- Efficient handling of attachments.
- Synchronization conflicts.
- Consistent state between the device and mail server.

Do not sacrifice correctness or battery efficiency for unnecessarily aggressive synchronization.

### Native Android integration

Integrate cleanly with:

- Android notifications.
- Notification channels.
- Notification actions.
- Contacts.
- Android Sharesheet.
- System file picker.
- Default application handling where relevant.
- App shortcuts.
- Widgets where useful.
- System credential management.
- Biometric security.
- Accessibility services and screen readers.

Use native Android mechanisms rather than inventing unnecessary substitutes.

---

## 5. Advanced features

Beyond the essentials, consider functionality that improves the everyday email experience.

Examples include:

- Unified search across accounts.
- Advanced search operators.
- Custom filters.
- Message templates.
- Sender identities and aliases.
- Newsletter management.
- Mailing-list handling.
- Unsubscribe actions.
- Attachment browsing.
- Attachment search.
- Contact suggestions.
- Smart folders.
- Optional encryption and signing support, such as OpenPGP or S/MIME.
- Conversation muting.
- Notification customization per account or folder.
- Configurable appearance and reading density.

Choose sensible priorities.

Not every advanced feature needs to be present in the first release, but the architecture should avoid unnecessary obstacles to future functionality.

---

## 6. Privacy and security

Privacy must be a foundational product principle.

### Non-negotiable requirements

- Fully free and open-source software.
- Publicly auditable source code.
- Appropriate open-source licenses for all included dependencies.
- No advertisements.
- No behavioral analytics.
- No tracking SDKs.
- No unnecessary telemetry.
- No mandatory proprietary services.
- No mandatory account with our project.
- No artificial feature restrictions or subscriptions.
- No sending email contents to third parties without explicit consent.
- No unnecessary Android permissions.

### Sensitive data

Handle account credentials, authentication tokens, cached messages, and attachments securely.

Use appropriate Android security mechanisms, including Android Keystore where applicable.

Consider the risks of:

- Lost or stolen devices.
- Local database exposure.
- Untrusted HTML.
- Malicious attachments.
- Phishing messages.
- Tracking pixels.
- Remote images.
- Untrusted links.
- Compromised mail servers.

Remote images and tracking mechanisms should be blocked by default, with clear user controls.

Protect user privacy without making ordinary email workflows unnecessarily inconvenient.

### Independence

Users must be free to choose their email provider.

The client should work directly with standard email infrastructure.

It should not require:

- Our own hosted account system.
- A proprietary synchronization server.
- A paid cloud API.
- A Google account for basic functionality.

Optional integrations must remain optional.

---

## 7. Intelligent features and AI

Explore intelligent functionality when it provides meaningful benefits.

Potential capabilities:

- Summarization of individual emails.
- Summaries of long conversations.
- Suggested replies.
- Extraction of appointments and deadlines.
- Detection of useful information such as order numbers or tracking links.
- OCR of attachments.
- Semantic search.
- Automatic categorization.
- Intelligent notification summaries.

However, **AI is not the primary purpose of the application**.

Do not compromise the reliability or simplicity of core email functionality to add AI features.

### Privacy requirements for AI

Prefer:

1. On-device processing.
2. Locally hosted models.
3. User-configured self-hosted AI endpoints.

Cloud AI integrations may be considered only as explicit opt-in extensions.

No email content should be silently transmitted to an external AI service.

AI functionality should remain optional and should not require a paid subscription.

---

## 8. Multi-agent team and responsibilities

You are working with two other engineering agents through Herdr.

**Collaboration is an explicit requirement, not an optional convenience.**

You are expected to coordinate meaningful work across the team.

### Claude Opus 5.5 — Technical and product lead

You are responsible for:

- Overall technical direction.
- Understanding the product vision.
- Architecture and system-level decisions.
- Product quality and user experience.
- Design coherence.
- Technical trade-offs.
- Coordinating contributions.
- Integration and final review.
- Maintaining a consistent quality standard.

Your strengths are particularly valuable for complex reasoning, understanding broad requirements, architectural decisions, and coordinating work across multiple areas.

Be aware of the risk of excessive planning or abstraction.

**Do not spend the project endlessly creating plans, specifications, and architectural documents. Build working software.**

You do not need to personally implement every component.

Your success depends on the combined output of the team.

### Codex — GPT-6.1 Sol Medium

Codex is your primary engineering collaborator.

Involve Codex heavily in:

- Android platform integration.
- Complex Kotlin implementation.
- Email protocol handling.
- Background synchronization.
- Concurrency and state management.
- Performance-sensitive components.
- Difficult bug investigation.
- Refactoring.
- Automated testing.
- Security-sensitive implementation review.
- Independent analysis of technical decisions.

Codex should own meaningful parts of the implementation.

Do not reduce its role to answering occasional questions or reviewing code after you have completed everything.

Encourage Codex to challenge architectural decisions where appropriate.

Its potential weakness in this collaboration is that strong implementation focus may not automatically translate into the desired level of visual polish.

Ensure that all technical contributions remain aligned with the product's refined UI/UX direction.

### OpenCode — Qwen3.8 27B

Qwen is a locally hosted engineering agent.

Use it actively for suitable parallel tasks, including:

- Focused Kotlin implementations.
- Reusable UI components.
- Isolated features.
- Unit tests.
- Test fixtures.
- Documentation.
- Codebase exploration.
- Localization.
- Supporting utilities.
- Repetitive but nontrivial engineering work.
- Evaluating alternative implementation approaches.

Its smaller local-model capacity may make very large-context, highly interconnected, or security-critical work more challenging.

Account for that when delegating tasks.

Provide focused context and well-defined interfaces where useful, and review its contributions proportionally to their risk.

Do not treat Qwen as incapable simply because it is smaller.

Evaluate its actual performance and adapt task assignment accordingly.

### Team collaboration principles

- Use all three agents meaningfully.
- Divide work according to actual strengths.
- Parallelize independent tasks.
- Avoid overlapping modifications that cause merge conflicts.
- Review each other's implementation decisions.
- Share discoveries and relevant context.
- Encourage constructive technical disagreements.
- Verify integration between independently developed components.
- Maintain a coherent product vision.
- Adapt responsibilities as the project progresses.

Use the communication and orchestration mechanisms available through Herdr.

Do not assume a specific Herdr API or command without inspecting the available environment.

**You are one engineering team building one cohesive product, not three agents producing unrelated prototypes.**

---

## 9. Engineering quality

You have freedom to select libraries, patterns, architecture, build tooling, and project organization, subject to the native Android requirement.

Use modern, maintainable Android engineering practices.

Prioritize:

- Reliability.
- Security.
- Performance.
- Readability.
- Maintainability.
- Testability.
- Simplicity where possible.
- Appropriate modularity.
- Efficient resource usage.
- Good user experience.

Avoid unnecessary architectural complexity and premature abstractions.

Consider mature FOSS components and existing email protocol libraries rather than rebuilding well-understood infrastructure without good reason.

### Performance

The application should feel fast and responsive on modern Android devices.

Pay attention to:

- Cold start.
- Smooth scrolling.
- Rendering large conversations.
- Compose recomposition.
- Background CPU usage.
- Battery consumption.
- Memory consumption.
- Database efficiency.
- Network efficiency.
- Attachment handling.

### Testing

Use appropriate automated and manual validation.

Relevant areas include:

- Email protocol interoperability.
- MIME parsing and rendering.
- Account authentication.
- Synchronization correctness.
- Offline behavior.
- Network failure recovery.
- Data persistence.
- Security-sensitive operations.
- UI behavior.
- Accessibility.
- Performance.

Do not claim functionality is working unless it has been appropriately verified.

### Deliverables

The repository should eventually provide:

- Complete application source code.
- Reproducible Android builds.
- Installable APKs.
- Clear setup and build instructions.
- Appropriate open-source licensing.
- Automated tests.
- Documentation for contributors.
- A clear explanation of supported and unsupported functionality.

Do not pretend an untested prototype is production-ready.

---

## 10. Long-term design system

This email client establishes the visual identity for a future Android application suite.

The shared design language should eventually be applicable to:

- Messages
- Phone
- Calendar
- Contacts
- Files
- Gallery
- Notes
- Clock
- Calculator
- Browser
- Keyboard

Consider the potential reuse of:

- Typography.
- Color tokens.
- Surface styles.
- Icons.
- Buttons.
- Form components.
- Dialogs.
- Menus.
- Bottom sheets.
- Navigation patterns.
- Animation principles.
- Haptic feedback.
- Theme management.

The future suite should feel like one coherent family of applications, even though each application is distributed and operated independently.

However, **do not prioritize building a massive reusable framework over delivering the first functional product**.

Create reusable components naturally as real needs emerge.

---

## 11. What success looks like

The application succeeds when it becomes something I genuinely prefer using over existing email clients.

It should be:

**Beautiful**

A refined, unmistakably modern Android interface inspired by ChatGPT and Grok.

**Functional**

A reliable email client capable of handling real accounts and daily communication.

**Private**

No advertising, unnecessary tracking, or dependency on proprietary infrastructure.

**Native**

A genuine Kotlin/Jetpack Compose Android application with excellent system integration.

**Independent**

Compatible with standard email services and self-hosted infrastructure.

**Maintainable**

A codebase suitable for long-term open-source development.

A beautiful interface without reliable email functionality is not sufficient.

A technically excellent email engine with a generic or unattractive interface is not sufficient either.

Both product design and engineering quality are essential.

---

## 12. Autonomy and execution

You have full authority to organize the engineering work.

Decide how to:

- Structure the project.
- Coordinate the agents.
- Assign responsibilities.
- Select libraries.
- Establish architecture.
- Define development milestones.
- Validate design decisions.
- Test the software.
- Resolve disagreements.
- Prioritize features.
- Manage technical debt.
- Integrate and review contributions.

Do not ask me to decide routine technical questions.

Investigate uncertainty, compare viable solutions, and choose the most appropriate approach.

Ask for input only when a decision materially changes the intended product experience, privacy guarantees, or project scope.

Avoid getting trapped in endless planning.

**Your job is to deliver working software, not merely a project proposal.**

Begin by collaborating with Codex and Qwen, understanding the available development environment, establishing a practical technical direction, and implementing the application.

The final ambition is straightforward:

**Build a genuinely excellent, fully native, privacy-first FOSS Android email client that feels as polished as ChatGPT or Grok, while remaining as independent and trustworthy as the best open-source software.**
