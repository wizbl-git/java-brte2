package com.wizbl.core.services.http;

import java.io.IOException;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.core.Wallet;
import com.wizbl.protos.Contract.ProposalCreateContract;
import com.wizbl.protos.Protocol.Transaction;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;


@Component
@Slf4j
public class ProposalCreateServlet extends HttpServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {

  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      String contract = request.getReader().lines()
          .collect(Collectors.joining(System.lineSeparator()));
      ProposalCreateContract.Builder build = ProposalCreateContract.newBuilder();
      JsonFormat.merge(contract, build);
      Transaction tx = wallet
          .createTransactionCapsule(build.build(), ContractType.ProposalCreateContract)
          .getInstance();
      response.getWriter().println(Util.printTransaction(tx));
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }
}
