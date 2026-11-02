const was:Record<string,number>={"HDMI-2M":1999,"KEY-1":6499,"MOUSE-1":3499,"SSD-1":12999,"CASE-1":2799};
export const SALE_ENDS=new Date(2026,10,27,23,59,59);
export function wasPrice(sku:string,now=Date.now()){const value=was[sku];return now>SALE_ENDS.getTime()||!value||value<=0?null:value;}
