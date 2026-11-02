import type { Metadata } from "next";
import { Fraunces, Outfit } from "next/font/google";
import "./style.css";
const fraunces=Fraunces({subsets:["latin"],variable:"--font-fraunces"});
const outfit=Outfit({subsets:["latin"],variable:"--font-outfit"});
export const metadata:Metadata={title:"Harbor orders",description:"A clear desk for every store pickup order."};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="en"><body className={`${fraunces.variable} ${outfit.variable}`}>{children}</body></html>}
