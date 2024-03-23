#include <boost/beast/core.hpp>
#include <boost/beast/websocket.hpp>
#include <boost/asio/connect.hpp>
#include <boost/asio/ip/tcp.hpp>
#include <cstdlib>
#include <iostream>
#include <string>
#include <climits>
#include <bitset>
#include <chrono>

namespace beast = boost::beast;
namespace http = beast::http;
namespace websocket = beast::websocket;
namespace net = boost::asio;
namespace ip = boost::asio::ip;
namespace chrono = std::chrono;
using tcp = ip::tcp;

int NUM_MESSAGES = 1000000;
int NUM_TRIES = 200;

void printBits(int64_t item) {
    std::bitset<64> bits(item);
    std::cout << bits << std::endl;
}

int main(int argc, char** argv)
{
    try
    {
        net::io_context ioc;

        tcp::resolver resolver{ioc};
        tcp::resolver::query query("localhost", "443");
        websocket::stream<tcp::socket> ws{ioc};
        auto const q_results = resolver.resolve(query);

        chrono::nanoseconds total{0};
        chrono::nanoseconds* results = new chrono::nanoseconds[NUM_TRIES];
        for (int i = 0; i < NUM_TRIES; i++) {

            auto ep = net::connect(ws.next_layer(), q_results);
            ws.handshake("localhost:443", "/");

            chrono::nanoseconds start = chrono::high_resolution_clock::now().time_since_epoch();
            beast::flat_buffer buffer;
            for (int i = 0; i < NUM_MESSAGES; i++) {
                buffer.clear();
                ws.read(buffer);
                unsigned char* ptr = (unsigned char*) buffer.cdata().data();
                int32_t res = (ptr[0] << 24) | (ptr[1] << 16) | (ptr[2] << 8) | ptr[3];
                if (i != res) {
                    std::cout << i << " != " << res << std::endl;
                    throw std::bad_exception();
                }
            }

            chrono::nanoseconds end = chrono::high_resolution_clock::now().time_since_epoch();

            auto diff = end - start;
            std::cout << "nanos ellapsed: " << (end - start).count() << std::endl;

            ws.close(websocket::close_code::normal);
            total += diff;
            results[i] = diff;
        }

        std::cout << "avg: " << total.count() / NUM_TRIES << std::endl;
        std::cout << "avg per read: " << total.count() / NUM_TRIES / NUM_MESSAGES << std::endl;
        std::cout << "[";
        for (int i = 0; i < NUM_TRIES; i++) {
            std::cout << results[i].count();
            if (i < NUM_TRIES - 1) {
                std::cout << ", ";
            }
        }
        std::cout << "]" << std::endl;
    }
    catch(std::exception const& e)
    {
        std::cerr << "Error: " << e.what() << std::endl;
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}