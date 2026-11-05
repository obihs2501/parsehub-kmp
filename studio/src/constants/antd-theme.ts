import { theme as antdTheme, ThemeConfig } from 'antd';

/**
 * Claude 风格主题：
 * 浅色 = 奶油纸色背景 + 赤陶橙主色 + 暖灰墨色文字 + 柔和大圆角
 * 深色 = 暖炭黑背景 + 同色系赤陶橙
 */
export const CLAUDE_COLORS = {
  primary: '#C96442', // 赤陶橙（terracotta）
  primaryHover: '#D97757',
  bgBase: '#FAF9F5', // 奶油纸色
  bgSecondary: '#F0EEE6', // 稍深的暖灰底
  text: '#3D3929', // 暖墨色
  textSecondary: '#87867F',
  border: '#E8E6DC',
  // 深色
  darkBg: '#262624',
  darkPanel: '#30302E',
  darkSidebar: '#1F1E1D',
  darkText: '#EDECE4',
  darkBorder: '#3F3E3A',
};

export function getAntdTheme(mode: 'light' | 'dark'): ThemeConfig {
  const dark = mode === 'dark';
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: dark ? CLAUDE_COLORS.primaryHover : CLAUDE_COLORS.primary,
      colorInfo: dark ? CLAUDE_COLORS.primaryHover : CLAUDE_COLORS.primary,
      colorLink: dark ? CLAUDE_COLORS.primaryHover : CLAUDE_COLORS.primary,
      colorBgBase: dark ? CLAUDE_COLORS.darkPanel : '#FFFFFF',
      colorBgLayout: dark ? CLAUDE_COLORS.darkBg : CLAUDE_COLORS.bgBase,
      colorTextBase: dark ? CLAUDE_COLORS.darkText : CLAUDE_COLORS.text,
      colorBorder: dark ? CLAUDE_COLORS.darkBorder : CLAUDE_COLORS.border,
      colorBorderSecondary: dark
        ? CLAUDE_COLORS.darkBorder
        : CLAUDE_COLORS.border,
      borderRadius: 10,
      fontFamily:
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    },
    cssVar: true,
    hashed: false,
  };
}

/** 兼容旧引用 */
export const ANTD_THEME = getAntdTheme('light');
