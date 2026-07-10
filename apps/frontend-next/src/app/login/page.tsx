"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Card, CardContent } from "@/components/ui/card";
import { AuthForm } from "@/components/AuthForm";
import { useSession } from "@/lib/auth-client";

export default function LoginPage() {
  const { data: session } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (session?.user) router.replace("/");
  }, [session?.user, router]);

  return (
    <div className="mx-auto flex min-h-[calc(100vh-3.5rem)] max-w-md items-center px-4">
      <Card className="w-full">
        <CardContent className="pt-6">
          <AuthForm onSuccess={() => router.replace("/")} />
        </CardContent>
      </Card>
    </div>
  );
}
