/*
 * Copyright (c) 2021 Paolo Boni
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.paoloboni.binance

import cats.Applicative
import cats.effect.{Clock, IO}
import cats.implicits._
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import io.circe.parser._
import io.github.paoloboni.binance.common._
import io.github.paoloboni.binance.fapi._
import io.github.paoloboni.binance.fapi.parameters._
import io.github.paoloboni.binance.fapi.response._
import io.github.paoloboni.integration._
import io.github.paoloboni.{Env, TestClient, WithClock}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import shapeless.tag
import eu.timepit.refined.refineMV

import java.time.Instant
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class FapiClientIntegrationTest extends AnyFreeSpec with Matchers with EitherValues with OptionValues with TestClient {

  "it should fire multiple requests when expected number of elements returned is above threshold" in new Env {
    withWiremockServer { server =>
      val from      = 1548806400000L
      val to        = 1548866280000L
      val symbol    = "ETHUSDT"
      val interval  = Interval.`1m`
      val threshold = 2

      stubInfoEndpoint(server)

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo(from.toString),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806400000, "104.41000000", "104.43000000", "104.27000000", "104.37000000", "185.23745000", 1548806459999, "19328.98599530", 80, "62.03712000", "6475.81062590", "0"],
                          |  [1548806460000, "104.38000000", "104.40000000", "104.33000000", "104.36000000", "211.54271000", 1548806519999, "22076.70809650", 68, "175.75948000", "18342.53313250", "0"],
                          |  [1548806520000, "104.36000000", "104.39000000", "104.36000000", "104.38000000", "59.74736000", 1548806579999, "6235.56895740", 28, "37.98161000", "3963.95268370", "0"]
                          |]
              """.stripMargin)
          )
      )

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548806520000"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806520000, "104.36000000", "104.39000000", "104.36000000", "104.38000000", "59.74736000", 1548806579999, "6235.56895740", 28, "37.98161000", "3963.95268370", "0"],
                          |  [1548836340000, "105.13000000", "105.16000000", "105.07000000", "105.10000000", "201.06821000", 1548836399999, "21139.17349190", 55, "35.40525000", "3722.13452500", "0"],
                          |  [1548836400000, "105.13000000", "105.14000000", "105.05000000", "105.09000000", "70.72517000", 1548836459999, "7432.93828700", 45, "36.68194000", "3855.32695710", "0"]
                          |]
              """.stripMargin)
          )
      )

      // NOTE: the last element in this response has timestamp equal to `to` time (from query) minus 1 second, so no further query should be performed
      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548836400000"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548836400000, "105.13000000", "105.14000000", "105.05000000", "105.09000000", "70.72517000", 1548836459999, "7432.93828700", 45, "36.68194000", "3855.32695710", "0"],
                          |  [1548866279000, "108.39000000", "108.39000000", "108.15000000", "108.22000000", "327.08359000", 1548866339999, "35415.40478090", 129, "163.42355000", "17699.38253540", "0"]
                          |]
              """.stripMargin)
          )
      )

      val config = prepareConfiguration(server)

      val result = BinanceClient
        .createFutureClient[IO](config)
        .use { gw =>
          gw
            .getKLines(
              common.parameters.KLines(
                symbol = symbol,
                interval = interval,
                startTime = Instant.ofEpochMilli(from),
                endTime = Instant.ofEpochMilli(to),
                limit = threshold
              )
            )
            .compile
            .toList
        }
        .unsafeRunSync()

      val responseFullJson = parse(
        """
          |[
          |  [1548806400000, "104.41000000", "104.43000000", "104.27000000", "104.37000000", "185.23745000", 1548806459999, "19328.98599530", 80, "62.03712000", "6475.81062590", "0"],
          |  [1548806460000, "104.38000000", "104.40000000", "104.33000000", "104.36000000", "211.54271000", 1548806519999, "22076.70809650", 68, "175.75948000", "18342.53313250", "0"],
          |  [1548806520000, "104.36000000", "104.39000000", "104.36000000", "104.38000000", "59.74736000", 1548806579999, "6235.56895740", 28, "37.98161000", "3963.95268370", "0"],
          |  [1548836340000, "105.13000000", "105.16000000", "105.07000000", "105.10000000", "201.06821000", 1548836399999, "21139.17349190", 55, "35.40525000", "3722.13452500", "0"],
          |  [1548836400000, "105.13000000", "105.14000000", "105.05000000", "105.09000000", "70.72517000", 1548836459999, "7432.93828700", 45, "36.68194000", "3855.32695710", "0"],
          |  [1548866279000, "108.39000000", "108.39000000", "108.15000000", "108.22000000", "327.08359000", 1548866339999, "35415.40478090", 129, "163.42355000", "17699.38253540", "0"]
          |]
        """.stripMargin
      ).value
      val expected = responseFullJson.as[List[KLine]].value

      result should have size 6
      result should contain theSameElementsInOrderAs expected

      server.verify(3, getRequestedFor(urlMatching("/fapi/v1/klines.*")))
    }
  }

  "it should be able to stream klines even with a threshold of 1" in new Env {
    withWiremockServer { server =>
      val from      = 1548806400000L
      val to        = 1548806640000L
      val symbol    = "ETHUSDT"
      val interval  = Interval.`1m`
      val threshold = 1

      stubInfoEndpoint(server)

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo(from.toString),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806400000, "104.41000000", "104.43000000", "104.27000000", "104.37000000", "185.23745000", 1548806459999, "19328.98599530", 80, "62.03712000", "6475.81062590", "0"]
                          |]
              """.stripMargin)
          )
      )

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548806459999"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806460000, "104.38000000", "104.40000000", "104.33000000", "104.36000000", "211.54271000", 1548806519999, "22076.70809650", 68, "175.75948000", "18342.53313250", "0"]
                          |]
              """.stripMargin)
          )
      )

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548806519999"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806520000, "104.36000000", "104.39000000", "104.36000000", "104.38000000", "59.74736000", 1548806579999, "6235.56895740", 28, "37.98161000", "3963.95268370", "0"]
                          |]
              """.stripMargin)
          )
      )

      // NOTE: the last element in this response has timestamp equal to `to` time (from query) minus 1 second, so no further query should be performed
      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548806579999"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806580000,"104.37000000","104.37000000","104.11000000","104.30000000","503.86391000",1548806639999,"52516.17118740",150,"275.42894000","28709.15114540","0"]
                          |]
              """.stripMargin)
          )
      )

      server.stubFor(
        get(urlPathEqualTo("/fapi/v1/klines"))
          .withQueryParams(
            Map(
              "symbol"    -> equalTo(symbol),
              "interval"  -> equalTo(interval.entryName),
              "startTime" -> equalTo("1548806639999"),
              "endTime"   -> equalTo(to.toString),
              "limit"     -> equalTo(threshold.toString)
            ).asJava
          )
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |  [1548806640000,"104.30000000","104.35000000","104.19000000","104.27000000","251.83113000",1548806699999,"26262.34369560",93,"102.24293000","10663.18343790","0"]
                          |]
              """.stripMargin)
          )
      )

      val config = prepareConfiguration(server)

      val result = BinanceClient
        .createFutureClient[IO](config)
        .use { gw =>
          gw
            .getKLines(
              common.parameters.KLines(
                symbol = symbol,
                interval = interval,
                startTime = Instant.ofEpochMilli(from),
                endTime = Instant.ofEpochMilli(to),
                limit = threshold
              )
            )
            .compile
            .toList
        }
        .unsafeRunSync()

      val responseFullJson = parse(
        """
          |[
          | [1548806400000,"104.41000000","104.43000000","104.27000000","104.37000000","185.23745000",1548806459999,"19328.98599530",80,"62.03712000","6475.81062590","0"],
          | [1548806460000,"104.38000000","104.40000000","104.33000000","104.36000000","211.54271000",1548806519999,"22076.70809650",68,"175.75948000","18342.53313250","0"],
          | [1548806520000,"104.36000000","104.39000000","104.36000000","104.38000000","59.74736000",1548806579999,"6235.56895740",28,"37.98161000","3963.95268370","0"],
          | [1548806580000,"104.37000000","104.37000000","104.11000000","104.30000000","503.86391000",1548806639999,"52516.17118740",150,"275.42894000","28709.15114540","0"]
          |]
        """.stripMargin
      ).value
      val expected = responseFullJson.as[List[KLine]].value

      result should have size 4
      result should contain theSameElementsInOrderAs expected

      server.verify(4, getRequestedFor(urlMatching("/fapi/v1/klines.*")))
    }
  }

  "it should return a list of prices" in new Env {
    withWiremockServer { server =>
      stubInfoEndpoint(server)

      server.stubFor(
        get("/fapi/v1/ticker/price")
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |[
                          |    {
                          |        "symbol": "ETHBTC",
                          |        "price": "0.03444300"
                          |    },
                          |    {
                          |        "symbol": "LTCBTC",
                          |        "price": "0.01493000"
                          |    }
                          |]
                      """.stripMargin)
          )
      )

      val config = prepareConfiguration(server)

      val result = BinanceClient
        .createFutureClient[IO](config)
        .use(_.getPrices())
        .unsafeRunSync()

      result should contain theSameElementsInOrderAs List(
        Price("ETHBTC", BigDecimal(0.03444300)),
        Price("LTCBTC", BigDecimal(0.01493000))
      )
    }
  }

  "it should return a single price" in new Env {
    withWiremockServer { server =>
      stubInfoEndpoint(server)

      server.stubFor(
        get("/fapi/v1/ticker/price")
          .willReturn(
            aResponse()
              .withStatus(200)
              .withBody("""
                          |{
                          |    "symbol": "ETHBTC",
                          |    "price": "0.03444300"
                          |}""".stripMargin)
          )
      )

      val config = prepareConfiguration(server)

      val param = PriceTickerParams(symbol = "ETHBTC")

      val result = BinanceClient
        .createFutureClient[IO](config)
        .use(_.getPrice(param))
        .unsafeRunSync()

      result shouldBe Price("ETHBTC", BigDecimal(0.03444300))
    }
  }

  "it should return the balance" in withWiremockServer { server =>
    import Env.{log, runtime}

    stubInfoEndpoint(server)

    val fixedTime = 1499827319559L

    val apiKey    = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A"
    val apiSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"

    server.stubFor(
      get(urlPathMatching("/fapi/v1/account"))
        .withHeader("X-MBX-APIKEY", equalTo(apiKey))
        .withQueryParam("recvWindow", equalTo("5000"))
        .withQueryParam("timestamp", equalTo(fixedTime.toString))
        .withQueryParam("signature", equalTo("6cd35332399b004466463b9ad65a112a14f31fb9ddfd5e19bd7298fbd491dbc7"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("""
                        |{
                        |    "feeTier": 0,
                        |    "canTrade": true,
                        |    "canDeposit": true,
                        |    "canWithdraw": true,
                        |    "updateTime": 0,
                        |    "totalInitialMargin": "0.00000000",
                        |    "totalMaintMargin": "0.00000000",
                        |    "totalWalletBalance": "23.72469206",
                        |    "totalUnrealizedProfit": "0.00000000",
                        |    "totalMarginBalance": "23.72469206",
                        |    "totalPositionInitialMargin": "0.00000000",
                        |    "totalOpenOrderInitialMargin": "0.00000000",
                        |    "totalCrossWalletBalance": "23.72469206",
                        |    "totalCrossUnPnl": "0.00000000",
                        |    "availableBalance": "23.72469206",
                        |    "maxWithdrawAmount": "23.72469206",
                        |    "assets": [
                        |        {
                        |            "asset": "USDT",
                        |            "walletBalance": "23.72469206",
                        |            "unrealizedProfit": "0.00000000",
                        |            "marginBalance": "23.72469206",
                        |            "maintMargin": "0.00000000",
                        |            "initialMargin": "0.00000000",
                        |            "positionInitialMargin": "0.00000000",
                        |            "openOrderInitialMargin": "0.00000000",
                        |            "crossWalletBalance": "23.72469206",
                        |            "crossUnPnl": "0.00000000",
                        |            "availableBalance": "23.72469206",
                        |            "maxWithdrawAmount": "23.72469206",
                        |            "marginAvailable": true
                        |        }
                        |    ],
                        |    "positions": [
                        |        {
                        |            "symbol": "BTCUSDT",
                        |            "initialMargin": "0",
                        |            "maintMargin": "0",
                        |            "unrealizedProfit": "0.00000000",
                        |            "positionInitialMargin": "0",
                        |            "openOrderInitialMargin": "0",
                        |            "leverage": "100",
                        |            "isolated": true,
                        |            "entryPrice": "0.00000",
                        |            "maxNotional": "250000",
                        |            "positionSide": "BOTH",
                        |            "positionAmt": "0"
                        |        }
                        |    ]
                        |}
                        """.stripMargin)
        )
    )

    val config = prepareConfiguration(server, apiKey = apiKey, apiSecret = apiSecret)

    implicit val withClock: WithClock[IO] = WithClock.create(stubTimer(fixedTime))

    val result = BinanceClient
      .createFutureClient[IO](config)
      .use(_.getBalance())
      .unsafeRunSync()

    result shouldBe FutureAccountInfoResponse(
      feeTier = 0,
      canTrade = true,
      canDeposit = true,
      canWithdraw = true,
      updateTime = 0L,
      totalInitialMargin = 0,
      totalMaintMargin = 0,
      totalWalletBalance = 23.72469206,
      totalUnrealizedProfit = 0.00000000,
      totalMarginBalance = 23.72469206,
      totalPositionInitialMargin = 0.00000000,
      totalOpenOrderInitialMargin = 0.00000000,
      totalCrossWalletBalance = 23.72469206,
      totalCrossUnPnl = 0.00000000,
      availableBalance = 23.72469206,
      maxWithdrawAmount = 23.72469206,
      assets = List(
        OwnedAsset(
          asset = tag[AssetTag][String]("USDT"),
          walletBalance = 23.72469206,
          unrealizedProfit = 0.00000000,
          marginBalance = 23.72469206,
          maintMargin = 0.00000000,
          initialMargin = 0.00000000,
          positionInitialMargin = 0.00000000,
          openOrderInitialMargin = 0.00000000,
          crossWalletBalance = 23.72469206,
          crossUnPnl = 0.00000000,
          availableBalance = 23.72469206,
          maxWithdrawAmount = 23.72469206,
          marginAvailable = true
        )
      ),
      positions = List(
        OpenPosition(
          symbol = "BTCUSDT",
          initialMargin = 0,
          maintMargin = 0,
          unrealizedProfit = 0.00000000,
          positionInitialMargin = 0,
          openOrderInitialMargin = 0,
          leverage = 100,
          isolated = true,
          entryPrice = 0.00000,
          maxNotional = 250000,
          positionSide = FuturePositionSide.BOTH,
          positionAmt = 0
        )
      )
    )
  }

  "it should be able to change the position mode" in withWiremockServer { server =>
    import Env.{log, runtime}

    stubInfoEndpoint(server)

    val fixedTime = 1499827319559L

    val apiKey    = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A"
    val apiSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"

    server.stubFor(
      post(urlPathMatching("/fapi/v1/positionSide/dual"))
        .withHeader("X-MBX-APIKEY", equalTo(apiKey))
        .withQueryParam("dualSidePosition", equalTo(true.toString))
        .withQueryParam("recvWindow", equalTo("5000"))
        .withQueryParam("timestamp", equalTo(fixedTime.toString))
        .withQueryParam("signature", equalTo("32789fb9396ee7087528096011b766b83de86afcd51a58b60d487d0e07a97676"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("""
                      |{
                      |    "code": 200,
                      |    "msg": "success"
                      |}""".stripMargin)
        )
    )

    val config = prepareConfiguration(server, apiKey = apiKey, apiSecret = apiSecret)

    implicit val withClock: WithClock[IO] = WithClock.create(stubTimer(fixedTime))

    val changePositionParams = ChangePositionModeParams(true)

    val result = BinanceClient
      .createFutureClient[IO](config)
      .use(_.changePositionMode(changePositionParams))
      .redeem(_ => false, _ => true)
      .unsafeRunSync()

    result shouldBe true

  }

  "it should be able to change the inital leverage" in withWiremockServer { server =>
    import Env.{log, runtime}

    stubInfoEndpoint(server)

    val fixedTime = 1499827319559L

    val apiKey    = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A"
    val apiSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"

    server.stubFor(
      post(urlPathMatching("/fapi/v1/leverage"))
        .withHeader("X-MBX-APIKEY", equalTo(apiKey))
        .withQueryParam("symbol", equalTo("BTCUSDT"))
        .withQueryParam("leverage", equalTo(100.toString))
        .withQueryParam("recvWindow", equalTo("5000"))
        .withQueryParam("timestamp", equalTo(fixedTime.toString))
        .withQueryParam("signature", equalTo("88ad5448acafacdda1da384cb71962785c43dc0b142ec550bbb6dcca53aa68d2"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("""
                      |{
                      |    "leverage": 100,
                      |    "maxNotionalValue": "1000000",
                      |    "symbol": "BTCUSDT"
                      |}
                      """.stripMargin)
        )
    )

    val config = prepareConfiguration(server, apiKey = apiKey, apiSecret = apiSecret)

    implicit val withClock: WithClock[IO] = WithClock.create(stubTimer(fixedTime))

    val changeLeverageParams = ChangeInitialLeverageParams(symbol = "BTCUSDT", leverage = refineMV(100))

    val result = BinanceClient
      .createFutureClient[IO](config)
      .use(_.changeInitialLeverage(changeLeverageParams))
      .unsafeRunSync()

    result shouldBe ChangeInitialLeverageResponse(
      symbol = "BTCUSDT",
      leverage = refineMV(100),
      maxNotionalValue = 1000000
    )

  }

  "it should create an order" in withWiremockServer { server =>
    import Env.{log, runtime}
    stubInfoEndpoint(server)

    val fixedTime = 1499827319559L

    val apiKey    = "vmPUZE6mv9SD5VNHk4HlWFsOr6aKE2zvsw0MuIgwCIPy6utIco14y7Ju91duEh8A"
    val apiSecret = "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"

    server.stubFor(
      post(urlPathMatching("/fapi/v1/order"))
        .withHeader("X-MBX-APIKEY", equalTo(apiKey))
        .withQueryParams(
          Map(
            "recvWindow" -> equalTo("5000"),
            "timestamp"  -> equalTo(fixedTime.toString),
            "signature"  -> equalTo("e41b485fdf1b2b4e7b50c24c82c8f37d639e52f542bb1deae9de0effe2863576")
          ).asJava
        )
        .willReturn(
          aResponse()
            .withStatus(201)
            .withBody("""{
                        |    "clientOrderId": "testOrder",
                        |    "cumQty": "0",
                        |    "cumQuote": "0",
                        |    "executedQty": "0",
                        |    "orderId": 22542179,
                        |    "avgPrice": "0.00000",
                        |    "origQty": "10",
                        |    "price": "0",
                        |    "reduceOnly": false,
                        |    "side": "BUY",
                        |    "positionSide": "SHORT",
                        |    "status": "NEW",
                        |    "stopPrice": "9300",
                        |    "closePosition": false,
                        |    "symbol": "BTCUSDT",
                        |    "timeInForce": "GTC",
                        |    "type": "TRAILING_STOP_MARKET",
                        |    "origType": "TRAILING_STOP_MARKET",
                        |    "activatePrice": "9020",
                        |    "priceRate": "0.3",
                        |    "updateTime": 1566818724722,
                        |    "workingType": "CONTRACT_PRICE",
                        |    "priceProtect": false
                        |}""".stripMargin)
        )
    )

    val config = prepareConfiguration(server, apiKey = apiKey, apiSecret = apiSecret)

    implicit val withClock: WithClock[IO] = WithClock.create(stubTimer(fixedTime))

    val result = BinanceClient
      .createFutureClient[IO](config)
      .use(
        _.createOrder(
          FutureOrderCreateParams.MARKET(
            symbol = "BTCUSDT",
            side = OrderSide.BUY,
            positionSide = FuturePositionSide.BOTH,
            quantity = 10
          )
        )
      )
      .unsafeRunSync()

    result shouldBe a[FutureOrderCreateResponse]
  }

  private def stubInfoEndpoint(server: WireMockServer) = {
    server.stubFor(
      get("/fapi/v1/exchangeInfo")
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("""
                        |{
                        |  "timezone":"UTC",
                        |  "serverTime":1621543436177,
                        |  "futuresType":"U_MARGINED",
                        |  "rateLimits": [
                        |    {
                        |      "rateLimitType": "REQUEST_WEIGHT",
                        |      "interval": "MINUTE",
                        |      "intervalNum": 1,
                        |      "limit": 1200
                        |    }
                        |  ],
                        |  "exchangeFilters":[],
                        |  "assets": [],
                        |  "symbols":[]
                        |}
                      """.stripMargin)
        )
    )
  }

  private def prepareConfiguration(server: WireMockServer, apiKey: String = "", apiSecret: String = "") =
    BinanceConfig("http", "localhost", server.port(), "/fapi/v1/exchangeInfo", apiKey, apiSecret)

  private def stubTimer(fixedTime: Long) = new Clock[IO] {
    override def applicative: Applicative[IO]  = ???
    override def monotonic: IO[FiniteDuration] = fixedTime.millis.pure[IO]
    override def realTime: IO[FiniteDuration]  = fixedTime.millis.pure[IO]
  }
}
