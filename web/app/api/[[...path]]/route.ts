import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";

const order = process.env.ORDER_API_URL ?? "http://localhost:8080";
const intake = process.env.INTAKE_API_URL ?? "http://localhost:8081";
const failure = (status=503) => NextResponse.json({code:"INTERNAL",message:"Could not reach Harbor",hint:"Wait a moment and try again. Your cart is still in this browser.",details:{}},{status});
async function proxy(req: NextRequest, method: string, upstream: string, body?: string, extras: Record<string,string> = {}) {
  try {
    const jar=await cookies(), token=jar.get("session")?.value;
    const headers:Record<string,string>={"X-Correlation-Id":crypto.randomUUID(),...extras};
    if(token)headers.Authorization=`Bearer ${token}`;
    if(body)headers["Content-Type"]="application/json";
    const response=await fetch(upstream,{method,headers,body,cache:"no-store"});
    const text=await response.text(); const result=new NextResponse(text,{status:response.status,headers:{"Content-Type":response.headers.get("content-type")??"application/json"}});
    const replay=response.headers.get("Idempotency-Replayed");if(replay)result.headers.set("Idempotency-Replayed",replay);return result;
  } catch { return failure(); }
}
async function handle(req:NextRequest,ctx:{params:Promise<{path?:string[]}>}) {
  const path=(await ctx.params).path??[]; const route="/"+path.join("/"); const method=req.method;
  if(route==="/auth/logout"&&method==="POST") { const out=NextResponse.json({ok:true});out.cookies.set("session","",{httpOnly:true,sameSite:"lax",path:"/",maxAge:0,secure:process.env.NODE_ENV==="production"});out.cookies.set("role","",{httpOnly:true,sameSite:"lax",path:"/",maxAge:0,secure:process.env.NODE_ENV==="production"});return out; }
  if(route==="/session"&&method==="GET"){const jar=await cookies(),role=jar.get("role")?.value;return role?NextResponse.json({role}):failure(401);}
  if(route==="/auth/login"&&method==="POST") {
    try {const raw=await req.text();const response=await fetch(`${order}/api/auth/login`,{method:"POST",headers:{"Content-Type":"application/json","X-Correlation-Id":crypto.randomUUID()},body:raw,cache:"no-store"});const value=await response.json();if(!response.ok)return NextResponse.json(value,{status:response.status});if(!value||typeof value!=="object"||typeof value.accessToken!=="string"||!["CUSTOMER","OPERATOR"].includes(value.role))return failure(502);const out=NextResponse.json({role:value.role});const options={httpOnly:true as const,sameSite:"lax" as const,path:"/",maxAge:900,secure:process.env.NODE_ENV==="production"};out.cookies.set("session",value.accessToken,options);out.cookies.set("role",value.role,options);return out;}catch{return failure();}
  }
  const query=new URL(req.url).searchParams;let suffix="";
  if(route==="/orders"&&method==="GET"){const allowed=new Set(["status","customerId","cursor","limit"]);for(const [k,v] of query)if(allowed.has(k)&&v.length<=200)suffix+=(suffix?"&":"?")+`${encodeURIComponent(k)}=${encodeURIComponent(v)}`;}
  if(route.startsWith("/orders/")&&!route.endsWith("/cancel")&&!route.endsWith("/status")){const id=path[1]??"";if(!/^ord_[a-z0-9]+$/.test(id))return NextResponse.json({code:"VALIDATION_ERROR",message:"That order link is not valid.",hint:"Open the order from your orders list.",details:{}},{status:400});}
  if(route.startsWith("/orders/")){const id=path[1]??"";if(!/^ord_[a-z0-9]+$/.test(id))return NextResponse.json({code:"VALIDATION_ERROR",message:"That order link is not valid.",hint:"Open the order from your orders list.",details:{}},{status:400});}
  const raw=method==="GET"?undefined:await req.text();const extra:Record<string,string>={};if(route==="/orders"&&method==="POST"){const key=req.headers.get("Idempotency-Key")??"";extra["Idempotency-Key"]=/^[A-Za-z0-9_-]{8,80}$/.test(key)?key:crypto.randomUUID();}
  if(route==="/drafts")return proxy(req,method,`${intake}/drafts`,raw);
  return proxy(req,method,`${order}/api${route}${suffix}`,raw,extra);
}
export {handle as GET,handle as POST,handle as PATCH};
