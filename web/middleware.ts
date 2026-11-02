import { NextRequest, NextResponse } from "next/server";
export function middleware(request: NextRequest) {
  const session = request.cookies.get("session")?.value;
  const pathname = request.nextUrl.pathname;
  if (pathname === "/login" && session) return NextResponse.redirect(new URL("/orders", request.url));
  if (pathname.startsWith("/orders") && !session) return NextResponse.redirect(new URL("/login", request.url));
  if (pathname.startsWith("/api/") && !["/api/auth/login", "/api/auth/logout"].includes(pathname) && !session) return NextResponse.json({code:"UNAUTHENTICATED",message:"Sign in is required.",hint:"Sign in, then try that again. Your cart stays in this browser.",details:{}},{status:401});
  return NextResponse.next();
}
export const config = { matcher: ["/orders/:path*", "/login", "/api/:path*"] };
