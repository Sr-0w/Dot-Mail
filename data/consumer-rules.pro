# Jakarta Mail provider and MIME handler classes are loaded by name from resource files/mailcap.
-keep class org.eclipse.angus.mail.handlers.** { public <init>(...); }
-keep class org.eclipse.angus.mail.imap.IMAPStore { public <init>(...); }
-keep class org.eclipse.angus.mail.imap.IMAPSSLStore { public <init>(...); }
-keep class org.eclipse.angus.mail.smtp.SMTPTransport { public <init>(...); }
-keep class org.eclipse.angus.mail.smtp.SMTPSSLTransport { public <init>(...); }
-keep class org.eclipse.angus.activation.** { public <init>(...); }
-keep class app.dot.mail.data.SyncWorker { public <init>(...); }
-keep class app.dot.mail.data.OutboxWorker { public <init>(...); }
