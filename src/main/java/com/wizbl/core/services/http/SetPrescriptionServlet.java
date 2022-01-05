package com.wizbl.core.services.http;

import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.api.GrpcAPI;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.crypto.Hash;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.protos.Contract;
import com.wizbl.protos.Protocol;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SetPrescriptionServlet extends HttpServlet {

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

    public Protocol.Transaction sign(Protocol.Transaction transaction, ECKey myKey) {
        Protocol.Transaction.Builder transactionBuilderSigned = transaction.toBuilder();
        byte[] hash = Sha256Hash.hash(transaction.getRawData().toByteArray());
        List<Protocol.Transaction.Contract> listContract = transaction.getRawData().getContractList();
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

    public static String parseMethod(String methodSign, String params) {
        byte[] selector = new byte[4];
        System.arraycopy(Hash.sha3(methodSign.getBytes()), 0, selector, 0, 4);
        System.out.println(methodSign + ":" + Hex.toHexString(selector));
        if (StringUtils.isEmpty(params)) {
            return Hex.toHexString(selector);
        }
        String result = Hex.toHexString(selector) + params;
        return result;
    }

    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Contract.TriggerSmartContract.Builder build = Contract.TriggerSmartContract.newBuilder();
        GrpcAPI.TransactionExtention.Builder trxExtBuilder = GrpcAPI.TransactionExtention.newBuilder();
        GrpcAPI.Return.Builder retBuilder = GrpcAPI.Return.newBuilder();
        String prescriptionCode ="";
        String parameter="";

        String[] contractAddresses = {
                "57121a73422d0e104eee9723c68e533c273cf90c2c6fb03f",
                "57121a73defa21dce99cfc9f6708b6cc0bd7aa2a9cc3d315",
                "57121a739a2743ff54dbb3296916aad4a55df5ffa375de5c",
                "57121a73df4ca10cb7d8e252f6e22a7a8af4ac5f2e97b136",
                "57121a73328ea8a52c08fb08ad07852b9917477a73c753d1",
                "57121a7305dc377e1fe85e4821c6def59c40508c3c396e48",
                "57121a73f3f783e6112ca028402c37d77b60ef2015d3f805",
                "57121a730842ec6d17860472a30873ed595a024469cd12ea",
                "57121a737ed3c14b22dad6304021245e1ded407bafddb20f",
                "57121a736dc5491b4b67a2bd70b580cf88b54b3a6774d273"
        };

        Random random = new Random();
        int index = random.nextInt(10);
        String contractAddress = contractAddresses[index];

        try {
            String prescription = request.getReader().lines()
                    .collect(Collectors.joining(System.lineSeparator()));
            String contract = "{\n" +
//                    "    \"contract_address\": \""+ Args.getInstance().getContractAddress()+"\",\n" +
                    "    \"contract_address\": \""+ contractAddress+"\",\n" +
                    "    \"function_selector\": \"set(string)\",\n" +
                    "    \"fee_limit\": 1000000,\n" +
                    "    \"owner_address\": \""+ Args.getInstance().getOwnerAddress()+"\"\n" +
                    "}";

            JsonFormat.merge(contract, build);
            JSONObject jsonObject = JSONObject.parseObject(contract);
            JSONObject prescriptionJsonObject = JSONObject.parseObject(prescription);

            Boolean prescriptionJson = prescriptionJsonObject.isEmpty();

            if("".equals(prescriptionJsonObject) || prescriptionJson || prescriptionJsonObject ==null){
                response.getWriter().println(Util.printSetPrescriptionError("JsonObject is null"));
                return;
            }

            String selector = jsonObject.getString("function_selector");

            prescriptionCode = prescriptionJsonObject.getString("prescriptionCode");
            System.out.println("prescriptionCode  " + prescriptionCode);
            logger.info("prescriptionCode : " + prescriptionCode);
            if("".equals(prescriptionCode) || prescriptionCode.isEmpty() || prescriptionCode ==null) {
                response.getWriter().println(Util.printSetPrescriptionError("prescriptionCode is null"));
                return;
            }

            parameter = prescriptionJsonObject.getString("parameter");
            System.out.println("parameter  " + new String(parameter.getBytes("euc-kr"), "euc-kr"));
            logger.info("init parameter : "+parameter);
            if("".equals(parameter) || parameter.isEmpty() || parameter ==null) {
                response.getWriter().println(Util.printSetPrescriptionError("parameter is null"));
                return;
            }

            //////////////////////parameter를 압축한다. - 시작//////////////////////

            String Hexparameter = Wizbl_v2_Util.concatWord(parameter);
            logger.info("Hex parameter : "+Hexparameter);

            //////////////////////parameter를 압축한다. - 끝//////////////////////

            String data = parseMethod(selector, Hexparameter);
            build.setData(ByteString.copyFrom(ByteArray.fromHexString(data)));

            long feeLimit = jsonObject.getLongValue("fee_limit");
            TransactionCapsule trxCap = wallet
                    .createTransactionCapsule(build.build(), Protocol.Transaction.Contract.ContractType.TriggerSmartContract);

            //////////////////////마지막 블럭 정보를 가지고옴 - 시작//////////////////////

            BlockCapsule block = wallet.getBlockByLatestNumTest();
            BlockCapsule.BlockId blockId = block.getBlockId();

            trxCap.setReference(blockId.getNum(), blockId.getBytes());
            long expiration = block.getTimeStamp() + Constant.TRANSACTION_DEFAULT_EXPIRATION_TIME;
            trxCap.setExpiration(expiration);
            trxCap.setTimestamp();

            //////////////////////마지막 블럭 정보를 가지고옴 - 끝//////////////////////

            Protocol.Transaction.Builder txBuilder = trxCap.getInstance().toBuilder();
            Protocol.Transaction.raw.Builder rawBuilder = trxCap.getInstance().getRawData().toBuilder();

            rawBuilder.setFeeLimit(feeLimit);
            txBuilder.setRawData(rawBuilder);

            Protocol.Transaction trx = wallet
                    .triggerContract(build.build(), new TransactionCapsule(txBuilder.build()), trxExtBuilder,
                            retBuilder);

            //////////////////////개인 PrivateKey를 하드코딩 한 이후 브로드캐스팅 함. - 시작//////////////////////


            if (trx.getRawData().getTimestamp() == 0) {
                long currentTime = System.currentTimeMillis();//*1000000 + System.nanoTime()%1000000;
                Protocol.Transaction.Builder builder2 = trx.toBuilder();
                Protocol.Transaction.raw.Builder rowBuilder = trx.getRawData().toBuilder();
                rowBuilder.setTimestamp(currentTime);
                builder2.setRawData(rowBuilder.build());
                trx = builder2.build();
            }

            String sPrivateKey = Args.getInstance().getSPrivateKey();
            byte[] privateKey = hexToByteArray(sPrivateKey);
            ECKey ecKey = ECKey.fromPrivate(privateKey);

            trx = sign(trx, ecKey);

            wallet.broadcastTransaction(trx);

            //////////////////////개인 PrivateKey를 하드코딩 한 이후 브로드캐스팅 함. - 끝//////////////////////

            trxExtBuilder.setTransaction(trx);
            trxExtBuilder.setTxid(trxCap.getTransactionId().getByteString());
            retBuilder.setResult(true).setCode(GrpcAPI.Return.response_code.SUCCESS);

        } catch (ContractValidateException e) {
            retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.CONTRACT_VALIDATE_ERROR)
                    .setMessage(ByteString.copyFromUtf8(e.getMessage()));
        } catch (Exception e) {
            retBuilder.setResult(false).setCode(GrpcAPI.Return.response_code.OTHER_ERROR)
                    .setMessage(ByteString.copyFromUtf8(e.getClass() + " : " + e.getMessage()));
        }

        trxExtBuilder.setResult(retBuilder);
        response.getWriter().println(Util.printTransactionTriggerExtention(trxExtBuilder.build(),prescriptionCode));
    }
}
