package com.czur.reference.application.http;

import com.czur.reference.application.bootstrap.ResultEmailConfig;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** 部署者自己的 SMTP 发信通道；邮件正文只包含可过期结果链接。 */
public final class SmtpResultEmailSender implements ResultEmailEndpoint.Sender {
    private final ResultEmailConfig config;

    public SmtpResultEmailSender(ResultEmailConfig config) {
        this.config = config;
    }

    @Override public void send(List<String> recipients, String resultUrl) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", config.host());
        properties.setProperty("mail.smtp.port", Integer.toString(config.port()));
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "true");
        properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "10000");
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username(), config.password());
            }
        });
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(config.from()));
            for (String recipient : recipients) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }
            message.setSubject("AI 会议结果链接", "UTF-8");
            message.setText("本场会议结果请通过以下链接查看（链接有有效期）：\n"
                    + resultUrl, "UTF-8");
            Transport.send(message);
        } catch (MessagingException failure) {
            throw new IOException("邮件发送失败", failure);
        }
    }
}
