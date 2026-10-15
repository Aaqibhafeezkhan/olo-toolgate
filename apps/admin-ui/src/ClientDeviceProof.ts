// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import {ControlClient} from './api';
import {extensionStatus} from './ChromeBridge';

export async function verifyCurrentDevice(client:ControlClient,signal:AbortSignal,userCode?:string):Promise<string>{
 let deviceId:string|undefined;
 if(!userCode){
  // This initial response supplies public identity only; its signature is never accepted by Control.
  const identity=await extensionStatus(signal,'proof','0'.repeat(64));
  if(identity.error||!identity.proof)throw Error('Device proof is unavailable. Upgrade the installed client and Chrome extension, then retry Connect.');
  deviceId=identity.proof.deviceId;
 }
 const challenge=await client.consoleDeviceChallenge(userCode?{userCode}:{deviceId},signal);
 const response=await extensionStatus(signal,'proof',challenge.nonce);
 const proof=response.proof;
 if(response.error||!proof||proof.nonce!==challenge.nonce||proof.deviceId!==challenge.deviceId||proof.keyFingerprint!==challenge.keyFingerprint)throw Error('This Chrome client does not match the requesting device. Retry Connect for this gateway.');
 const verified=await client.verifyConsoleDevice(proof,signal);
 await client.adminSession();
 return verified.deviceId;
}
