/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        bg: "#0b0d10",
        panel: "#14171c",
        surface: "#181c23",
        line: "#232830",
        lineHover: "#353c47",
        ink: "#e7eaf0",
        ink2: "#b9c0cc",
        mute: "#7a8492",
        accent: { DEFAULT: "#3b82f6", soft: "#1e3a8a" },
        sev: {
          critical: "#ef4444",
          high: "#f97316",
          medium: "#eab308",
          low: "#10b981",
        },
      },
      fontFamily: {
        display: ["Inter", "-apple-system", "BlinkMacSystemFont", "system-ui", "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "monospace"],
      },
      keyframes: {
        pulseRing: {
          "0%": { transform: "scale(0.8)", opacity: "0.8" },
          "100%": { transform: "scale(2.4)", opacity: "0" },
        },
        slideIn: {
          "0%": { transform: "translateY(-8px)", opacity: "0" },
          "100%": { transform: "translateY(0)", opacity: "1" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-200% 0" },
          "100%": { backgroundPosition: "200% 0" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-6px)" },
        },
        glow: {
          "0%, 100%": { boxShadow: "0 0 20px rgba(124,58,237,0.3)" },
          "50%": { boxShadow: "0 0 40px rgba(124,58,237,0.6)" },
        },
      },
      animation: {
        pulseRing: "pulseRing 2s cubic-bezier(0.4,0,0.6,1) infinite",
        slideIn: "slideIn 0.3s ease-out",
        shimmer: "shimmer 3s linear infinite",
        float: "float 3s ease-in-out infinite",
        glow: "glow 3s ease-in-out infinite",
      },
      backgroundImage: {
        "grid": "linear-gradient(rgba(124,58,237,0.08) 1px, transparent 1px), linear-gradient(90deg, rgba(124,58,237,0.08) 1px, transparent 1px)",
        "radial-fade": "radial-gradient(ellipse at top, rgba(124,58,237,0.15), transparent 60%)",
      },
    },
  },
  plugins: [],
};
