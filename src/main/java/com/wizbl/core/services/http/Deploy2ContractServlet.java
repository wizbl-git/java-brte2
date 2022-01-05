package com.wizbl.core.services.http;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.api.GrpcAPI;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.protos.Contract.CreateSmartContract;
import com.wizbl.protos.Protocol.SmartContract;
import com.wizbl.protos.Protocol.SmartContract.ABI;
import com.wizbl.protos.Protocol.Transaction;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;


@Component
@Slf4j
public class Deploy2ContractServlet extends HttpServlet {

  @Autowired
  private Wallet wallet;
//////////////////////아래 파일 실행시 필요한 메소드. - 시작//////////////////////

  public byte[] hexToByteArray(String hex) {
    if (hex == null || hex.length() == 0) {
      return null;
    }

    byte[] ba = new byte[hex.length() / 2];
    for (int i = 0; i < ba.length; i++) {
      ba[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }

    return ba;
  }

  public Transaction sign(Transaction transaction, ECKey myKey) {
    Transaction.Builder transactionBuilderSigned = transaction.toBuilder();
    byte[] hash = Sha256Hash.hash(transaction.getRawData().toByteArray());
    List<Transaction.Contract> listContract = transaction.getRawData().getContractList();
    for (int i = 0; i < listContract.size(); i++) {
      ECKey.ECDSASignature signature = myKey.sign(hash);
      ByteString bsSign = ByteString.copyFrom(signature.toByteArray());
      transactionBuilderSigned.addSignature(
              bsSign);//Each contract may be signed with a different private key in the future.
    }

    transaction = transactionBuilderSigned.build();
    return transaction;
  }

  //////////////////////아래 파일 실행시 필요한 메소드. - 끝//////////////////////

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    CreateSmartContract.Builder build = CreateSmartContract.newBuilder();
    GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
    GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();

    try {
      String contract = request.getReader().lines()
              .collect(Collectors.joining(System.lineSeparator()));

      JSONObject jsonObject = JSONObject.parseObject(contract);
      byte[] ownerAddress = ByteArray.fromHexString(jsonObject.getString("owner_address"));
      build.setOwnerAddress(ByteString.copyFrom(ownerAddress));
      build
              .setCallTokenValue(jsonObject.getLongValue("call_token_value"))
              .setTokenId(jsonObject.getLongValue("token_id"));

      String abi = jsonObject.getString("abi");
      StringBuffer abiSB = new StringBuffer("{");
      abiSB.append("\"entrys\":");
      abiSB.append(abi);
      abiSB.append("}");
      ABI.Builder abiBuilder = ABI.newBuilder();
      JsonFormat.merge(abiSB.toString(), abiBuilder);

      long feeLimit = jsonObject.getLongValue("fee_limit");

      SmartContract.Builder smartBuilder = SmartContract.newBuilder();
      smartBuilder
              .setAbi(abiBuilder)
              .setCallValue(jsonObject.getLongValue("call_value"))
              .setConsumeUserResourcePercent(jsonObject.getLongValue("consume_user_resource_percent"))
              .setOriginEnergyLimit(jsonObject.getLongValue("origin_energy_limit"));
      if (!ArrayUtils.isEmpty(ownerAddress)) {
        smartBuilder.setOriginAddress(ByteString.copyFrom(ownerAddress));
      }

      String jsonByteCode = jsonObject.getString("bytecode");
      if (jsonObject.containsKey("parameter")) {
        jsonByteCode += jsonObject.getString("parameter");
      }
      byte[] byteCode = ByteArray.fromHexString(jsonByteCode);
      if (!ArrayUtils.isEmpty(byteCode)) {
        smartBuilder.setBytecode(ByteString.copyFrom(byteCode));
      }
      String name = jsonObject.getString("name");
      if (!Strings.isNullOrEmpty(name)) {
        smartBuilder.setName(name);
      }

      build.setNewContract(smartBuilder);
      TransactionCapsule trxCap = wallet
              .createTransactionCapsule(build.build(), ContractType.CreateSmartContract);

      //////////////////////마지막 블럭 정보를 가지고옴 - 시작//////////////////////

      BlockCapsule block = wallet.getBlockByLatestNumTest();
      BlockCapsule.BlockId blockId = block.getBlockId();

      trxCap.setReference(blockId.getNum(), blockId.getBytes());
      long expiration = block.getTimeStamp() + Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;
      trxCap.setExpiration(expiration);
      trxCap.setTimestamp();

      //////////////////////마지막 블럭 정보를 가지고옴 - 끝//////////////////////

      Transaction.Builder txBuilder = trxCap.getInstance().toBuilder();
      Transaction.raw.Builder rawBuilder = trxCap.getInstance().getRawData().toBuilder();

      rawBuilder.setFeeLimit(feeLimit);
      txBuilder.setRawData(rawBuilder);

      Transaction trx = wallet.deployContract(build.build(), new TransactionCapsule(txBuilder.build()));

      //////////////////////개인 PrivateKey를 하드코딩 한 이후 브로드캐스팅 함. - 시작//////////////////////

      if (trx.getRawData().getTimestamp() == 0) {
        long currentTime = System.currentTimeMillis();//*1000000 + System.nanoTime()%1000000;
        Transaction.Builder builder2 = trx.toBuilder();
        Transaction.raw.Builder rowBuilder = trx.getRawData().toBuilder();
        rowBuilder.setTimestamp(currentTime);
        builder2.setRawData(rowBuilder.build());
        trx = builder2.build();
      }

//      String sPrivateKey = "af7c83e40cc67a355852b44051fc9e34452375ae569d5c18dd62e3859b9be229";
      String sPrivateKey = "481d576ca6a91f1be88f5c809944de1d7fa5d065c08f854db894c3b8e804221b";
      byte[] privateKey = hexToByteArray(sPrivateKey);
      ECKey ecKey = ECKey.fromPrivate(privateKey);

      trx = sign(trx, ecKey);

      wallet.broadcastTransaction(trx);

      //////////////////////개인 PrivateKey를 하드코딩 한 이후 브로드캐스팅 함. - 끝//////////////////////
      trxExtBuilder.setTransaction(trx);
      trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());

      retBuilder.setResult(true).setCode(GrpcAPI.Return.response_code.SUCCESS);
    } catch (Exception e) {
      retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.OTHER_ERROR).setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
      logger.debug("Exception: {}", e.getMessage());
    }
    trxExtBuilder.setResult(retBuilder);
    try {
      response.getWriter().println(Util.printTransactionDeployExtention(trxExtBuilder.build()));
    } catch (IOException e) {
      logger.debug("IOException: {}", e.getMessage());
    }
  }
}