"use client";

import type { ReactNode } from "react";
import { AuthProvider } from "@/lib/auth-client";
import { Navbar } from "@/components/Navbar";
import { PromoBanner } from "@/components/PromoBanner";
import { Footer } from "@/components/Footer";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <AuthProvider>
      <div className="min-h-screen">
        <PromoBanner />
        <Navbar />
        <main>{children}</main>
        <Footer />
      </div>
    </AuthProvider>
  );
}
