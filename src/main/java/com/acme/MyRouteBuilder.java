package com.acme;

import java.util.Map;
import javax.jms.ConnectionFactory;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Message;
import org.apache.camel.Body;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultProducerTemplate;    
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.language.JsonPathExpression;
import org.apache.camel.model.dataformat.JsonLibrary;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import java.io.InputStreamReader;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;




/**
 * A Camel Java DSL Router
 */
public class MyRouteBuilder extends RouteBuilder {
    
    private static final ScriptEngineManager manager = new ScriptEngineManager();
    private static final ScriptEngine engine = manager.getEngineByName("JavaScript");
    private static final AtomicReference<Map> accountsRef = new AtomicReference<Map>(null);
    
    protected void initializeEngine(CamelContext ctx) 
	throws IOException, ScriptException, Exception {
	ProducerTemplate producer = new DefaultProducerTemplate(ctx);
	producer.start();
	engine.put("producer", producer);
	engine.put("accountsRef", accountsRef);
	engine.eval(new InputStreamReader(new ClassPathResource("js/bank.js").getInputStream()));
    }

    /**
     * Let's configure the Camel routing rules using Java code...
     */
    public void configure() throws IOException, ScriptException, Exception {
	initializeEngine(getContext());
	
	ConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616");
	// Note we can explicit name the component
	getContext().addComponent("jms", JmsComponent.jmsComponentAutoAcknowledge(connectionFactory));

	onException(java.lang.IllegalStateException.class)
	    .convertBodyTo(String.class)
	    .to("file:out/bank/failures?fileName=${date:now:yyyyMMdd-hhmmssSSSS}.${in.header.operation}")
	    .to("jms:queue:das.failures");
	    
	interceptFrom("seda:bank.*")
	    .marshal().json(JsonLibrary.Jackson)
	    .choice()
	    .when().jsonpath("$..from[?(@.customer == $.to.customer)]")
	    // Checking if from and to is the same customer
	    .process(new Processor(){
		    public void process(Exchange outExchange) throws Exception{
			throw new java.lang.IllegalStateException("Can't transfer within same account!");
		    }
		})
	    .otherwise()
	    // Trying to perform transaction. Throwing exception if not possible
	    .process(new Processor(){
		    public void process(Exchange outExchange) {
			Message message = outExchange.getIn();
			String operation = message.getHeader("operation").toString();
			Map accountFrom = (Map)accountsRef.get().get(new JsonPathExpression("$.from.customer").evaluate(outExchange).toString());
			Map accountTo = (Map)accountsRef.get().get(new JsonPathExpression("$.to.customer").evaluate(outExchange).toString());
			Double amount = (Double)new JsonPathExpression("$.amount").evaluate(outExchange);
			System.out.println(accountFrom.get("balance"));
			switch(operation.toLowerCase()){
			case "send": {
			    //FIXME: SHOUL BE TRANSACTED HERE
			    if((Double)accountFrom.get("balance") < amount){
				throw new java.lang.IllegalStateException("Insufficient funds");
			    } else {
				accountFrom.put("balance", (Double)accountFrom.get("balance") - amount);
				accountTo.put("balance", (Double)accountTo.get("balance") + amount);
			    }
			};
			case "receive": {
			    //FIXME: SHOUL BE TRANSACTED HERE
			    if((Double)accountTo.get("balance") < (Double)new JsonPathExpression("$.amount").evaluate(outExchange)){
				throw new java.lang.IllegalStateException("Insufficient funds");
			    } else {
				//FIXME: SHOUL BE TRANSACTED HERE
				accountFrom.put("balance", (Double)accountFrom.get("balance") + amount);
				accountTo.put("balance", (Double)accountTo.get("balance") - amount);
			    }
			};
			case "withdraw": {
			    if((Double)accountFrom.get("balance") < (Double)new JsonPathExpression("$.amount").evaluate(outExchange)){
				throw new java.lang.IllegalStateException("Insufficient funds");
			    } else {
				accountFrom.put("balance", (Double)accountFrom.get("balance") - amount);
			    }
			}
			}
			new java.lang.Exception("From and To can't be the same");
		    }
		})
	    .end();
	    
        from("seda:bank.send")
	    .log("${in.header.operation}:${in.body}");

        from("seda:bank.receive")
	    .log("${in.header.operation}:${in.body}");

        from("seda:bank.withdraw")
	    .log("${in.header.operation}:${in.body}");

    }

}
