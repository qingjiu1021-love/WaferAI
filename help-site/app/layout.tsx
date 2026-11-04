import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "WaferAI 帮助中心",
  description: "WaferAI 手机应用的账号、使用指南与常见问题。",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="antialiased">{children}</body>
    </html>
  );
}
