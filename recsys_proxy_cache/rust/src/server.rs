#[macro_use]
extern crate lazy_static;
extern crate lfu;

use std::collections::HashMap;
use rand::Rng;
use tonic::{transport::Server, Request, Response, Status};
use recsys::{ScoreRequest, ScoreResponse, Context, Item, Field};
use recsys::recsys_proxy_cache_server::{RecsysProxyCache, RecsysProxyCacheServer};
use lfu::LFUCache;

mod recsys {
    tonic::include_proto!("recsys"); // The string specified here must match the proto package name
}

static mut lfu_cache: Option<&lfu::LFUCache<&'static str, f64>> = None;

enum ScoreRequestType {
    contextual,
    non_contextual
}

#[derive(Debug, Default)]
pub struct GrpcServer {}

#[tonic::async_trait]
impl RecsysProxyCache for GrpcServer {
    async fn get_scores(
        &self, 
        request: Request<ScoreRequest>
    ) -> Result<Response<ScoreResponse>, Status> {
        let r = request.into_inner();
        let context = r.context.unwrap_or(
            recsys::Context {
                fields: vec![]
            }
        );
        if r.items.len() <= 0 {
            let status = Status::invalid_argument("its all invalid");
            return Err(status);
        }

        let scores = process_request(&context, &r.items).await;
        let reply = recsys::ScoreResponse {
            scores: scores
        };
        Ok(Response::new(reply))
    }
}

async fn process_request(
    context: &recsys::Context, 
    items: &Vec<recsys::Item>
) -> Vec<f64> {
    unsafe {
        if lfu_cache.is_none() {
            let tmp = LFUCache::with_capacity(50_000_000).unwrap();
            lfu_cache = Some(&LFUCache::with_capacity(50_000_000).unwrap());
        }
    }
    let mut rng = rand::thread_rng();
    unsafe {
        if lfu_cache.is_some() {
            let value = lfu_cache.unwrap().get(&"abc");
        }
    }
    return items.into_iter().map(|_| rng.gen::<f64>()).collect();
}



#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "0.0.0.0:50051".parse()?;
    let recsys_proxy = GrpcServer::default();

    Server::builder()
        .add_service(RecsysProxyCacheServer::new(recsys_proxy))
        .serve(addr)
        .await?;

    Ok(())
}